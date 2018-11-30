package mesosphere.marathon
package core.task.tracker.impl

import java.time.Clock
import java.util.concurrent.TimeoutException

import akka.Done
import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.pipe
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.appinfo.TaskCounts
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceChange, InstanceDeleted, InstanceUpdateEffect, InstanceUpdateOpResolver, InstanceUpdateOperation, InstanceUpdated}
import mesosphere.marathon.core.leadership.LeaderDeferrable
import mesosphere.marathon.core.task.tracker.impl.InstanceTrackerActor.{RepositoryStateUpdated, UpdateContext}
import mesosphere.marathon.core.task.tracker.{InstanceTracker, InstanceTrackerUpdateStepProcessor}
import mesosphere.marathon.metrics.{Metrics, SettableGauge}
import mesosphere.marathon.state.{PathId, Timestamp}
import mesosphere.marathon.storage.repository.InstanceView

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object InstanceTrackerActor {
  def props(
    metrics: ActorMetrics,
    taskLoader: InstancesLoader,
    updateStepProcessor: InstanceTrackerUpdateStepProcessor,
    stateOpResolver: InstanceUpdateOpResolver,
    repository: InstanceView,
    clock: Clock): Props = {
    Props(new InstanceTrackerActor(metrics, taskLoader, updateStepProcessor, stateOpResolver, repository, clock))
  }

  /** Query the current [[InstanceTracker.SpecInstances]] from the [[InstanceTrackerActor]]. */
  private[impl] case object List

  private[impl] case class Get(instanceId: Instance.Id)

  /** Add a new subscription for sender to instance updates */
  @LeaderDeferrable private[impl] case object Subscribe
  private[impl] case object Unsubscribe

  private[impl] case class UpdateContext(deadline: Timestamp, operation: InstanceUpdateOperation) {
    def appId: PathId = operation.instanceId.runSpecId
    def instanceId: Instance.Id = operation.instanceId
  }

  private[tracker] class ActorMetrics(val metrics: Metrics) {
    // We can't use Metrics as we need custom names for compatibility.
    val stagedTasksMetric: SettableGauge = metrics.settableGauge("instances.staged")
    val runningTasksMetric: SettableGauge = metrics.settableGauge("instances.running")

    def resetMetrics(): Unit = {
      stagedTasksMetric.setValue(0)
      runningTasksMetric.setValue(0)
    }
  }
  private case class RepositoryStateUpdated(effect: InstanceUpdateEffect)
}

/**
  * Holds the current in-memory version of all task state. It gets informed of task state changes
  * after they have been persisted.
  */
private[impl] class InstanceTrackerActor(
    metrics: InstanceTrackerActor.ActorMetrics,
    instanceLoader: InstancesLoader,
    updateStepProcessor: InstanceTrackerUpdateStepProcessor,
    updateOperationResolver: InstanceUpdateOpResolver,
    repository: InstanceView,
    clock: Clock) extends Actor with Stash with StrictLogging {

  // Internal state of the tracker. It is set after initialization.
  var instancesBySpec: InstanceTracker.InstancesBySpec = _
  var counts: TaskCounts = _

  override val supervisorStrategy = OneForOneStrategy() { case _: Exception => Escalate }

  override def preStart(): Unit = {
    super.preStart()

    logger.info(s"${getClass.getSimpleName} is starting. Task loading initiated.")
    metrics.resetMetrics()

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

      metrics.stagedTasksMetric.setValue(counts.tasksStaged.toLong)
      metrics.runningTasksMetric.setValue(counts.tasksRunning.toLong)

      context.become(initialized)

      unstashAll()

    case Status.Failure(cause) =>
      // escalate this failure
      logger.error("InstanceTracker failed to load tasks because: ", cause)
      throw new IllegalStateException("Error while loading tasks", cause)

    case _: AnyRef =>
      stash()
  }

  var subscribers: Set[ActorRef] = Set.empty

  private[this] def initialized: Receive = {

    LoggingReceive.withLabel("initialized") {
      case InstanceTrackerActor.Subscribe =>
        if (!subscribers.contains(sender)) {
          subscribers += sender
          // Send initial "created" events to subscribers so they can have a consistent view of instance state
          instancesBySpec.allInstances.foreach { instance =>
            sender ! InstanceUpdated(instance, None, Nil)
          }
        }

      case InstanceTrackerActor.Unsubscribe =>
        subscribers -= sender

      case InstanceTrackerActor.List =>
        sender() ! instancesBySpec

      case InstanceTrackerActor.Get(instanceId) =>
        sender() ! instancesBySpec.instance(instanceId)

      case update: UpdateContext =>
        logger.info(s"Processing ${update.operation.shortString}")

        val originalSender = sender
        val updateEffect = resolveUpdateEffect(update)
        import scala.concurrent.ExecutionContext.Implicits.global

        updateEffect match {
          case InstanceUpdateEffect.Update(instance, _, _) =>
            repository.store(instance).onComplete {
              case Failure(NonFatal(ex)) =>
                originalSender ! Status.Failure(ex)
              case Success(_) =>
                self.tell(RepositoryStateUpdated(updateEffect), originalSender)
            }

          case InstanceUpdateEffect.Expunge(instance, _) =>
            logger.debug(s"Received expunge for ${instance.instanceId}")
            repository.delete(instance.instanceId).onComplete {
              case Failure(NonFatal(ex)) =>
                originalSender ! Status.Failure(ex)
              case Success(_) =>
                self.tell(RepositoryStateUpdated(updateEffect), originalSender)
            }

          case InstanceUpdateEffect.Failure(cause) =>
            // Used if a task status update for a non-existing task is processed or the processing timed out.
            // Since we did not change the task state, we inform the sender directly of the failed operation.
            originalSender ! Status.Failure(cause)

          case _ =>
            self forward RepositoryStateUpdated(updateEffect)
        }

      case RepositoryStateUpdated(effect) =>
        val maybeChange: Option[InstanceChange] = effect match {
          case InstanceUpdateEffect.Update(instance, oldInstance, events) =>
            logger.info(s"Persisted ${instance.instanceId} state: ${instance.state}")
            updateApp(instance.runSpecId, instance.instanceId, newInstance = Some(instance))
            Some(InstanceUpdated(instance, lastState = oldInstance.map(_.state), events))

          case InstanceUpdateEffect.Expunge(instance, events) =>
            logger.info(s"Received expunge for ${instance.instanceId}")
            updateApp(instance.runSpecId, instance.instanceId, newInstance = None)
            Some(InstanceDeleted(instance, lastState = None, events))

          case InstanceUpdateEffect.Noop(_) | InstanceUpdateEffect.Failure(_) =>
            None
        }

        maybeChange.foreach(notifySubscribers)

        val originalSender = sender()
        import scala.concurrent.ExecutionContext.Implicits.global

        maybeChange.map { change =>
          updateStepProcessor.process(change).recover {
            case NonFatal(cause) =>
              // since we currently only use ContinueOnErrorSteps, we can simply ignore failures here
              logger.warn("updateStepProcessor.process failed", cause)
              Done
          }
        }.getOrElse(Future.successful(Done)).foreach { _ =>
          originalSender ! effect
        }
    }
  }

  def notifySubscribers(change: InstanceChange): Unit =
    subscribers.foreach { _ ! change }

  def resolveUpdateEffect(update: UpdateContext): InstanceUpdateEffect = {
    if (update.deadline <= clock.now()) {
      InstanceUpdateEffect.Failure(new TimeoutException(s"Timeout: ${update.operation} for app [${update.appId}] and ${update.instanceId}."))
    } else {
      val maybeInstance = instancesBySpec.instance(update.operation.instanceId)
      updateOperationResolver.resolve(maybeInstance, update.operation)
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
      case None =>
        logger.debug(s"Expunging $instanceId from the in-memory state")
        instancesBySpec.updateApp(appId)(_.withoutInstance(instanceId))
      case Some(instance) =>
        logger.debug(s"Updating $instanceId in-memory state: ${instance.state}")
        instancesBySpec.updateApp(appId)(_.withInstance(instance))
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
    metrics.stagedTasksMetric.setValue(counts.tasksStaged.toLong)
    metrics.runningTasksMetric.setValue(counts.tasksRunning.toLong)
  }
}
