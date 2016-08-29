package mesosphere.marathon.core.task.tracker.impl

import akka.actor.ActorRef
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.TaskStateOp
import mesosphere.marathon.state.{ PathId, Timestamp }

import scala.concurrent.{ ExecutionContext, Future }

private[tracker] object TaskOpProcessor {
  case class Operation(deadline: Timestamp, sender: ActorRef, taskId: Instance.Id, stateOp: TaskStateOp) {
    def appId: PathId = taskId.runSpecId
  }
}

/**
  * Processes durable operations on tasks.
  */
private[tracker] trait TaskOpProcessor {
  def process(op: TaskOpProcessor.Operation)(implicit ec: ExecutionContext): Future[Unit]
}
