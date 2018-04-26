package mesosphere.marathon
package core.task.tracker.impl

import akka.Done
import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import akka.event.LoggingReceive
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.appinfo.TaskCounts
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{ InstanceChange, InstanceDeleted, InstanceUpdateEffect, InstanceUpdateOperation, InstanceUpdated }
import mesosphere.marathon.core.task.tracker.impl.InstanceTrackerActor.ForwardTaskOp
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, InstanceTrackerUpdateStepProcessor }
import mesosphere.marathon.metrics.AtomicGauge
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.storage.repository.InstanceRepository

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal

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
  private[impl] case class ForwardTaskOp(deadline: Timestamp, instanceId: Instance.Id, op: InstanceUpdateOperation)

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

  private[this] def initialized: Receive = {

    LoggingReceive.withLabel("initialized") {
      case InstanceTrackerActor.List =>
        sender() ! instancesBySpec

      case InstanceTrackerActor.Get(instanceId) =>
        sender() ! instancesBySpec.instance(instanceId)

      case ForwardTaskOp(deadline, instanceId, instanceUpdateOp) =>
        val op = InstanceOpProcessor.Operation(deadline, sender(), instanceId, instanceUpdateOp)
        updaterRef.forward(InstanceUpdateActor.ProcessInstanceOp(op))

      case InstanceTrackerActor.StateChanged(ack) =>
        import context.dispatcher

        val maybeChange: Future[Option[InstanceChange]] = ack.effect match {
          case effect @ InstanceUpdateEffect.Update(instance, _, _) =>
            repository.store(instance) // first store to the repository
              .map(_ => Some(effect))
              .recoverWith(tryToRecoverRepositoryFailure(effect))
              .map(trackerEffect => // update task tracked based on the repository update result
                trackerEffect.flatMap(ef => {
                  updateApp(ef.instance.runSpecId, ef.instance.instanceId, newInstance = Some(ef.instance))
                  Some(InstanceUpdated(ef.instance, lastState = ef.oldState.map(_.state), ef.events))
                }))

          case effect @ InstanceUpdateEffect.Expunge(instance, _) =>
            logger.debug(s"Received expunge for ${instance.instanceId}")
            repository.delete(instance.instanceId) // first store to the repository
              .map(_ => Some(effect))
              .recoverWith(tryToRecoverRepositoryFailure(effect))
              .map(trackerEffect => // update task tracked based on the repository update result
                trackerEffect.flatMap(ef => {
                  updateApp(ef.instance.runSpecId, ef.instance.instanceId, newInstance = None)
                  Some(InstanceDeleted(ef.instance, lastState = None, ef.events))
                }))

          case InstanceUpdateEffect.Noop(_) | InstanceUpdateEffect.Failure(_) =>
            Future.successful(None)
        }

        val originalSender = sender()

        import context.dispatcher
        maybeChange.flatMap { changeOpt =>
          changeOpt.map { change =>
            updateStepProcessor.process(change).recover {
              case NonFatal(cause) =>
                // since we currently only use ContinueOnErrorSteps, we can simply ignore failures here
                logger.warn("updateStepProcessor.process failed", cause)
                Done
            }
          }.getOrElse(Future.successful(Done))
        }.onComplete {
          case Success(_) =>
            ack.sendAck()
            originalSender ! (())
          case Failure(e) =>
            originalSender ! Status.Failure(e)
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
    println(instancesBySpec)
    counts = updatedCounts

    // this is run on any state change
    metrics.stagedCount.setValue(counts.tasksStaged.toLong)
    metrics.runningCount.setValue(counts.tasksRunning.toLong)
  }

  /**
    * If we encounter failure, we try to reload the effected task to make sure that the taskTracker
    * is up-to-date. We signal failure to the sender if the state is not as expected.
    *
    * If reloading the tasks also fails, the operation does fail.
    *
    * This tries to isolate failures that only effect certain tasks, e.g. errors in the serialization logic
    * which are only triggered for a certain combination of fields.
    */
  private def tryToRecoverRepositoryFailure(update: InstanceUpdateEffect.Update)(
    implicit
    ec: ExecutionContext): PartialFunction[Throwable, Future[Option[InstanceUpdateEffect.Update]]] = {
    case NonFatal(e) =>
      repository.get(update.instance.instanceId).map {
        case Some(repositoryInstance) if repositoryInstance == update.instance => Some(update)
        // update did not get through
        case _ => None
      }
  }

  /**
    * If we encounter failure, we try to reload the effected task to make sure that the taskTracker
    * is up-to-date. We signal failure to the sender if the state is not as expected.
    *
    * If reloading the tasks also fails, the operation does fail.
    *
    * This tries to isolate failures that only effect certain tasks, e.g. errors in the serialization logic
    * which are only triggered for a certain combination of fields.
    */
  private def tryToRecoverRepositoryFailure(expunge: InstanceUpdateEffect.Expunge)(
    implicit
    ec: ExecutionContext): PartialFunction[Throwable, Future[Option[InstanceUpdateEffect.Expunge]]] = {
    case NonFatal(_) =>
      repository.get(expunge.instance.instanceId).map {
        case None => Some(expunge)
        case _ => None
      }
  }
}
