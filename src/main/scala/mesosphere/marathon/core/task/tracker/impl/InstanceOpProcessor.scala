package mesosphere.marathon.core.task.tracker.impl

import akka.actor.ActorRef
import mesosphere.marathon.core.instance.{ Instance, InstanceStateOp }
import mesosphere.marathon.state.{ PathId, Timestamp }

import scala.concurrent.{ ExecutionContext, Future }

private[tracker] object InstanceOpProcessor {
  case class Operation(deadline: Timestamp, sender: ActorRef, instanceId: Instance.Id, stateOp: InstanceStateOp) {
    def appId: PathId = instanceId.runSpecId
  }
}

/**
  * Processes durable operations on tasks.
  */
private[tracker] trait InstanceOpProcessor {
  def process(op: InstanceOpProcessor.Operation)(implicit ec: ExecutionContext): Future[Unit]
}
