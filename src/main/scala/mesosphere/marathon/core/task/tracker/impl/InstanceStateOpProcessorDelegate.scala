package mesosphere.marathon
package core.task.tracker.impl
//scalastyle:off
import java.time.Clock

import akka.actor.ActorRef
import akka.util.Timeout
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.task.tracker.impl.InstanceTrackerActor.ForwardTaskOp
import mesosphere.marathon.core.task.tracker.{ InstanceTrackerConfig, InstanceStateOpProcessor }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal
//scalastyle:on
/**
  * Implements the [[InstanceStateOpProcessor]] trait by sending messages to the TaskTracker actor.
  */
private[tracker] class InstanceStateOpProcessorDelegate(
    clock: Clock,
    conf: InstanceTrackerConfig,
    instanceTrackerRef: ActorRef)
  extends InstanceStateOpProcessor {

  import mesosphere.marathon.core.async.ExecutionContexts.global

  private[impl] implicit val timeout: Timeout = conf.internalTaskUpdateRequestTimeout().milliseconds

  override def process(stateOp: InstanceUpdateOperation): Future[InstanceUpdateEffect] = {
    import akka.pattern.ask

    val instanceId: Instance.Id = stateOp.instanceId
    val deadline = clock.now + timeout.duration
    val op: ForwardTaskOp = InstanceTrackerActor.ForwardTaskOp(deadline, instanceId, stateOp)
    (instanceTrackerRef ? op).mapTo[InstanceUpdateEffect].recover {
      case NonFatal(e) =>
        throw new RuntimeException(s"while asking for $op on runSpec [${instanceId.runSpecId}] and $instanceId", e)
    }
  }
}
