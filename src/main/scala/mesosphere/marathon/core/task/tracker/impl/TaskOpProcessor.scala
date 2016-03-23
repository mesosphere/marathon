package mesosphere.marathon.core.task.tracker.impl

import akka.actor.ActorRef
import mesosphere.marathon.core.task.{ Task, TaskStateOp }
import mesosphere.marathon.state.{ PathId, Timestamp }

import scala.concurrent.{ ExecutionContext, Future }

private[tracker] object TaskOpProcessor {
  case class Operation(deadline: Timestamp, sender: ActorRef, taskId: Task.Id, stateOp: TaskStateOp) {
    def appId: PathId = taskId.appId
  }
}

/**
  * Processes durable operations on tasks.
  */
private[tracker] trait TaskOpProcessor {
  def process(op: TaskOpProcessor.Operation)(implicit ec: ExecutionContext): Future[Unit]
}
