package mesosphere.marathon
package core.task.tracker.impl

import akka.Done
import akka.actor.{ActorRef, Status}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.{InstanceUpdateEffect, InstanceUpdateOpResolver}
import mesosphere.marathon.core.task.tracker.InstanceTrackerConfig

import scala.concurrent.{ExecutionContext, Future}

/**
  * Processes durable operations on tasks by storing the updated tasks in or removing them from the task repository
  */
private[tracker] class InstanceOpProcessorImpl(
    instanceTrackerRef: ActorRef,
    stateOpResolver: InstanceUpdateOpResolver,
    config: InstanceTrackerConfig) extends InstanceOpProcessor with StrictLogging {
  import InstanceOpProcessor._

  override def process(op: Operation)(implicit ec: ExecutionContext): Future[Done] = {
    logger.debug(s"Process $op")
    val stateChange = stateOpResolver.resolve(op.op)

    import akka.pattern.ask

    import scala.concurrent.duration._

    stateChange.flatMap {
      case change: InstanceUpdateEffect.Expunge =>
        // Used for task termination or as a result from a UpdateStatus action.
        // The expunge is propagated to the instanceTracker which informs the sender about the success (see Ack).
        implicit val taskTrackerQueryTimeout: Timeout = config.internalTaskTrackerRequestTimeout().milliseconds

        val msg = InstanceTrackerActor.StateChanged(InstanceTrackerActor.Ack(op.sender, change))
        logger.debug(s"Notify instance tracker actor: msg=$msg")
        val f = (instanceTrackerRef ? msg).map(_ => Done)
        f.onComplete(_ => logger.debug(s"Expunged $change"))
        f

      case change: InstanceUpdateEffect.Failure =>
        // Used if a task status update for a non-existing task is processed.
        // Since we did not change the task state, we inform the sender directly of the failed operation.
        op.sender ! Status.Failure(change.cause)
        Future.successful(Done)

      case change: InstanceUpdateEffect.Noop =>
        // Used if a task status update does not result in any changes.
        // Since we did not change the task state, we inform the sender directly of the success of
        // the operation.
        op.sender ! change
        Future.successful(Done)

      case change: InstanceUpdateEffect.Update =>
        // Used for a create or as a result from a UpdateStatus action.
        // The update is propagated to the taskTracker which in turn informs the sender about the success (see Ack).
        implicit val taskTrackerQueryTimeout: Timeout = config.internalTaskTrackerRequestTimeout().milliseconds

        val msg = InstanceTrackerActor.StateChanged(InstanceTrackerActor.Ack(op.sender, change))
        logger.debug(s"Notify instance tracker actor: msg=$msg")
        val f = (instanceTrackerRef ? msg).map(_ => Done)
        f.onComplete(_ => logger.debug(s"Stored $change"))
        f
    }
  }
}
