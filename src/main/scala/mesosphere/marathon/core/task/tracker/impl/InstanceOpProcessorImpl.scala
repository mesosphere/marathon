package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ ActorRef, Status }
import akka.util.Timeout
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.instance.update.{ InstanceChangedEventsGenerator, InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.task.tracker.impl.InstanceOpProcessorImpl.InstanceUpdateOpResolver
import mesosphere.marathon.core.task.tracker.{ InstanceTracker, InstanceTrackerConfig }
import mesosphere.marathon.storage.repository.InstanceRepository
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.immutable.Seq
import scala.util.control.NonFatal

private[tracker] object InstanceOpProcessorImpl {

  /**
    * Maps a [[InstanceUpdateOperation]] to the appropriate [[InstanceUpdateEffect]].
    *
    * @param directInstanceTracker a TaskTracker instance that goes directly to the correct taskTracker
    *                          without going through the WhenLeaderActor indirection.
    */
  class InstanceUpdateOpResolver(directInstanceTracker: InstanceTracker, clock: Clock) {
    private[this] val log = LoggerFactory.getLogger(getClass)
    private[this] val eventsGenerator = InstanceChangedEventsGenerator

    /**
      * Maps the TaskStateOp
      *
      * * a TaskStateChange.Failure if the task does not exist OR ELSE
      * * delegates the TaskStateOp to the existing task that will then determine the state change
      */
    def resolve(op: InstanceUpdateOperation)(implicit ec: ExecutionContext): Future[InstanceUpdateEffect] = {
      op match {
        case op: InstanceUpdateOperation.LaunchEphemeral => updateIfNotExists(op.instanceId, op.instance)
        case op: InstanceUpdateOperation.LaunchOnReservation => updateExistingInstance(op)
        case op: InstanceUpdateOperation.MesosUpdate => updateExistingInstance(op)
        case op: InstanceUpdateOperation.ReservationTimeout => updateExistingInstance(op)
        case op: InstanceUpdateOperation.Reserve => updateIfNotExists(op.instanceId, op.instance)
        case op: InstanceUpdateOperation.ForceExpunge => expungeInstance(op.instanceId)
        case op: InstanceUpdateOperation.Revert =>
          Future.successful(InstanceUpdateEffect.Update(op.instance, oldState = None, events = Nil))
      }
    }

    private[this] def updateIfNotExists(instanceId: Instance.Id, updatedInstance: Instance)(
      implicit
      ec: ExecutionContext): Future[InstanceUpdateEffect] = {
      directInstanceTracker.instance(instanceId).map {
        case Some(existingInstance) =>
          InstanceUpdateEffect.Failure( //
            new IllegalStateException(s"$instanceId of app [${instanceId.runSpecId}] already exists"))

        case None =>
          val events = eventsGenerator.events(updatedInstance.state.status, updatedInstance, task = None, clock.now())
          InstanceUpdateEffect.Update(updatedInstance, oldState = None, events)
      }
    }

    private[this] def updateExistingInstance(op: InstanceUpdateOperation) //
    (implicit ec: ExecutionContext): Future[InstanceUpdateEffect] = {
      directInstanceTracker.instance(op.instanceId).map {
        case Some(existingInstance) =>
          existingInstance.update(op)

        case None =>
          val id = op.instanceId
          InstanceUpdateEffect.Failure(new IllegalStateException(s"$id of app [${id.runSpecId}] does not exist"))
      }
    }

    private[this] def expungeInstance(id: Instance.Id)(implicit ec: ExecutionContext): Future[InstanceUpdateEffect] = {
      directInstanceTracker.instance(id).map {
        case Some(existingInstance: Instance) =>
          val events = eventsGenerator.events(InstanceStatus.Killed, existingInstance, task = None, clock.now())
          InstanceUpdateEffect.Expunge(existingInstance, events)

        case None =>
          log.info("Ignoring ForceExpunge for [{}], task does not exist", id)
          InstanceUpdateEffect.Noop(id)
      }
    }
  }
}

/**
  * Processes durable operations on tasks by storing the updated tasks in or removing them from the task repository
  */
private[tracker] class InstanceOpProcessorImpl(
    instanceTrackerRef: ActorRef,
    repository: InstanceRepository,
    stateOpResolver: InstanceUpdateOpResolver,
    config: InstanceTrackerConfig) extends InstanceOpProcessor {
  import InstanceOpProcessor._

  private[this] val log = LoggerFactory.getLogger(getClass)

  override def process(op: Operation)(implicit ec: ExecutionContext): Future[Unit] = {
    val stateChange = stateOpResolver.resolve(op.op)
    stateChange.flatMap {
      case change: InstanceUpdateEffect.Expunge =>
        // Used for task termination or as a result from a UpdateStatus action.
        // The expunge is propagated to the instanceTracker which informs the sender about the success (see Ack).
        repository.delete(change.instance.instanceId).map { _ => InstanceTrackerActor.Ack(op.sender, change) }
          .recoverWith(tryToRecover(op)(
            expectedState = None, oldState = Some(change.instance), change.events))
          .flatMap { ack: InstanceTrackerActor.Ack => notifyTaskTrackerActor(ack) }

      case change: InstanceUpdateEffect.Failure =>
        // Used if a task status update for a non-existing task is processed.
        // Since we did not change the task state, we inform the sender directly of the failed operation.
        op.sender ! Status.Failure(change.cause)
        Future.successful(())

      case change: InstanceUpdateEffect.Noop =>
        // Used if a task status update does not result in any changes.
        // Since we did not change the task state, we inform the sender directly of the success of
        // the operation.
        op.sender ! change
        Future.successful(())

      case change: InstanceUpdateEffect.Update =>
        // Used for a create or as a result from a UpdateStatus action.
        // The update is propagated to the taskTracker which in turn informs the sender about the success (see Ack).
        repository.store(change.instance).map { _ => InstanceTrackerActor.Ack(op.sender, change) }
          .recoverWith(tryToRecover(op)(
            expectedState = Some(change.instance), oldState = change.oldState, change.events))
          .flatMap { ack => notifyTaskTrackerActor(ack) }
    }
  }

  private[this] def notifyTaskTrackerActor(ack: InstanceTrackerActor.Ack)(
    implicit
    ec: ExecutionContext): Future[Unit] = {

    import akka.pattern.ask

    import scala.concurrent.duration._
    implicit val taskTrackerQueryTimeout: Timeout = config.internalTaskTrackerRequestTimeout().milliseconds

    val msg = InstanceTrackerActor.StateChanged(ack)
    (instanceTrackerRef ? msg).map(_ => ())
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
  private[this] def tryToRecover(op: Operation)(
    expectedState: Option[Instance], oldState: Option[Instance], events: Seq[MarathonEvent])(
    implicit
    ec: ExecutionContext): PartialFunction[Throwable, Future[InstanceTrackerActor.Ack]] = {

    case NonFatal(cause) =>
      def ack(actualState: Option[Instance], effect: InstanceUpdateEffect): InstanceTrackerActor.Ack = {
        val msg =
          if (expectedState == actualState) effect
          else InstanceUpdateEffect.Failure(cause)
        InstanceTrackerActor.Ack(op.sender, msg)
      }

      log.warn(s"${op.instanceId} of app [${op.instanceId.runSpecId}]: try to recover from failed ${op.op}", cause)

      repository.get(op.instanceId).map {
        case Some(instance) =>
          val effect = InstanceUpdateEffect.Update(instance, oldState, events)
          ack(Some(instance), effect)
        case None =>
          val effect = oldState match {
            case Some(oldInstanceState) => InstanceUpdateEffect.Expunge(oldInstanceState, events)
            case None => InstanceUpdateEffect.Noop(op.instanceId)
          }
          ack(None, effect)
      }.recover {
        case NonFatal(loadingFailure) =>
          log.warn(
            s"${op.instanceId} of app [${op.instanceId.runSpecId}]: instance reloading failed as well",
            loadingFailure)
          throw cause
      }
  }
}
