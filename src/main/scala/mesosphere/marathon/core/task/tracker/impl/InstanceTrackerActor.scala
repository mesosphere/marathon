package mesosphere.marathon
package core.task.tracker.impl

import akka.Done
import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import akka.event.LoggingReceive
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.appinfo.TaskCounts
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceDeleted, InstanceUpdateEffect, InstanceUpdateOperation, InstanceUpdated}
import mesosphere.marathon.core.task.tracker.impl.InstanceTrackerActor.{UpdateContext, RepositoryStateUpdated}
import mesosphere.marathon.core.task.tracker.{InstanceTracker, InstanceTrackerUpdateStepProcessor}
import mesosphere.marathon.metrics.AtomicGauge
import mesosphere.marathon.state.{PathId, Timestamp}
import mesosphere.marathon.storage.repository.InstanceRepository

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object InstanceTrackerActor {
  def props(
    metrics: ActorMetrics,
    taskLoader: InstancesLoader,
    updateStepProcessor: InstanceTrackerUpdateStepProcessor,
    taskUpdaterProps: ActorRef => Props,
    repository: InstanceRepository): Props = {
    Props(new InstanceTrackerActor(metrics, taskLoader, updateStepProcessor, taskUpdaterProps, repository))
  }

  /** Query the current [[InstanceTracker.SpecInstances]] from the [[InstanceTrackerActor]]. */
  private[impl] case object List

  private[impl] case class Get(instanceId: Instance.Id)

  /** Forward an update operation to the child [[InstanceUpdateActor]]. */
  private[impl] case class UpdateContext(deadline: Timestamp, op: InstanceUpdateOperation) {
    def appId: PathId = op.instanceId.runSpecId
    def instanceId: Instance.Id = op.instanceId
  }

  /** Describes where and what to send after an update event has been processed by the [[InstanceTrackerActor]]. */
  private[impl] case class Ack(initiator: ActorRef, effect: InstanceUpdateEffect) extends StrictLogging {
    def sendAck(): Unit = {
      val msg = effect match {
        case InstanceUpdateEffect.Failure(cause) => Status.Failure(cause)
        case _ => effect
      }
      logger.debug(s"Send acknowledgement: initiator=$initiator msg=$msg")
      initiator ! msg
    }
  }

  /** Inform the [[InstanceTrackerActor]] of a task state change (after persistence). */
  private[impl] case class StateChanged(ack: Ack)

  private[tracker] class ActorMetrics {
    // We can't use Metrics as we need custom names for compatibility.
    val stagedCount: AtomicGauge = AtomicGauge("service.mesosphere.marathon.task.staged.count")
    val runningCount: AtomicGauge = AtomicGauge("service.mesosphere.marathon.task.running.count")

    def resetMetrics(): Unit = {
      stagedCount.setValue(0)
      runningCount.setValue(0)
    }
  }
  private case class RepositoryStateUpdated(ack: Ack)
}

/**
  * Holds the current in-memory version of all task state. It gets informed of task state changes
  * after they have been persisted.
  *
  * It also spawns the [[InstanceUpdateActor]] as a child and forwards update operations to it.
  */
private[impl] class InstanceTrackerActor(
    metrics: InstanceTrackerActor.ActorMetrics,
    instanceLoader: InstancesLoader,
    updateStepProcessor: InstanceTrackerUpdateStepProcessor,
    instanceUpdaterProps: ActorRef => Props,
    repository: InstanceRepository) extends Actor with Stash with StrictLogging {

  private[this] val updaterRef = context.actorOf(instanceUpdaterProps(self), "updater")

  // Internal state of the tracker. It is set after initialization.
  var instancesBySpec: InstanceTracker.InstancesBySpec = _
  var counts: TaskCounts = _

  override val supervisorStrategy = OneForOneStrategy() { case _: Exception => Escalate }

  override def preStart(): Unit = {
    super.preStart()

    logger.info(s"${getClass.getSimpleName} is starting. Task loading initiated.")
    metrics.resetMetrics()

    import akka.pattern.pipe
    import context.dispatcher
    instanceLoader.load().pipeTo(self)
  }

  override def postStop(): Unit = {
    metrics.resetMetrics()

    super.postStop()
  }

  override def receive: Receive = initializing

  private[this] def initializing: Receive = LoggingReceive.withLabel("initializing") {
    case initialInstances: InstanceTracker.InstancesBySpec =>
      logger.info("Instances loading complete.")

      instancesBySpec = initialInstances
      counts = TaskCounts(initialInstances.allInstances, healthStatuses = Map.empty)

      metrics.stagedCount.setValue(counts.tasksStaged.toLong)
      metrics.runningCount.setValue(counts.tasksRunning.toLong)

      context.become(initialized)

      unstashAll()

    case Status.Failure(cause) =>
      // escalate this failure
      throw new IllegalStateException("while loading tasks", cause)

    case stashMe: AnyRef =>
      stash()
  }

  @SuppressWarnings(Array("all"))
  private[this] def initialized: Receive = {

    LoggingReceive.withLabel("initialized") {
      case InstanceTrackerActor.List =>
        sender() ! instancesBySpec

      case InstanceTrackerActor.Get(instanceId) =>
        sender() ! instancesBySpec.instance(instanceId)

      case update @ UpdateContext =>
        updaterRef.forward(update)

      case InstanceTrackerActor.StateChanged(ack) =>
        import context.dispatcher

        ack.effect match {
          case InstanceUpdateEffect.Update(instance, _, _) =>
            val originalSender = sender
            repository.store(instance).onComplete {
              case Failure(NonFatal(ex)) =>
                originalSender ! Status.Failure(ex)
              case Success(_) =>
                self.tell(RepositoryStateUpdated(ack), originalSender)
            }

          case InstanceUpdateEffect.Expunge(instance, _) =>
            val originalSender = sender
            logger.debug(s"Received expunge for ${instance.instanceId}")
            repository.delete(instance.instanceId).onComplete {
              case Failure(NonFatal(ex)) =>
                originalSender ! Status.Failure(ex)
              case Success(_) =>
                self.tell(RepositoryStateUpdated(ack), originalSender)
            }

          case _ =>
            self forward RepositoryStateUpdated(ack)
        }

      case RepositoryStateUpdated(ack) =>
        val maybeChange: Option[InstanceChange] = ack.effect match {
          case InstanceUpdateEffect.Update(instance, oldInstance, events) =>
            updateApp(instance.runSpecId, instance.instanceId, newInstance = Some(instance))
            Some(InstanceUpdated(instance, lastState = oldInstance.map(_.state), events))

          case InstanceUpdateEffect.Expunge(instance, events) =>
            logger.debug(s"Received expunge for ${instance.instanceId}")
            updateApp(instance.runSpecId, instance.instanceId, newInstance = None)
            Some(InstanceDeleted(instance, lastState = None, events))

          case InstanceUpdateEffect.Noop(_) | InstanceUpdateEffect.Failure(_) =>
            None
        }

        val originalSender = sender()

        import context.dispatcher
        maybeChange.map { change =>
          updateStepProcessor.process(change).recover {
            case NonFatal(cause) =>
              // since we currently only use ContinueOnErrorSteps, we can simply ignore failures here
              logger.warn("updateStepProcessor.process failed", cause)
              Done
          }
        }.getOrElse(Future.successful(Done)).foreach { _ =>
          ack.sendAck()
          originalSender ! (())
        }
    }
  }

  /**
    * Update the state of an app or pod and its instances.
    *
    * @param appId Identifier of the app or pod to update.
    * @param instanceId The identifier of the instance that is removed, added or updated.
    * @param newInstance A new or updated instance, or none if it is expunged.
    */
  def updateApp(appId: PathId, instanceId: Instance.Id, newInstance: Option[Instance]): Unit = {
    val updatedAppInstances = newInstance match {
      case None => instancesBySpec.updateApp(appId)(_.withoutInstance(instanceId))
      case Some(instance) => instancesBySpec.updateApp(appId)(_.withInstance(instance))
    }

    val updatedCounts = {
      val oldInstance = instancesBySpec.instance(instanceId)
      // we do ignore health counts
      val oldTaskCount = TaskCounts(oldInstance.to[Seq], healthStatuses = Map.empty)
      val newTaskCount = TaskCounts(newInstance.to[Seq], healthStatuses = Map.empty)
      counts + newTaskCount - oldTaskCount
    }

    instancesBySpec = updatedAppInstances
    counts = updatedCounts

    // this is run on any state change
    metrics.stagedCount.setValue(counts.tasksStaged.toLong)
    metrics.runningCount.setValue(counts.tasksRunning.toLong)
  }
}
