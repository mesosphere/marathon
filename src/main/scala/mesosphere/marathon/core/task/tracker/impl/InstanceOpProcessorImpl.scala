package mesosphere.marathon.core.task.tracker.impl

import akka.actor.{ ActorRef, Status }
import akka.util.Timeout
import mesosphere.marathon.core.event.MarathonEvent
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOpResolver }
import mesosphere.marathon.core.task.tracker.InstanceTrackerConfig
import mesosphere.marathon.storage.repository.InstanceRepository
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.immutable.Seq
import scala.util.control.NonFatal

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
