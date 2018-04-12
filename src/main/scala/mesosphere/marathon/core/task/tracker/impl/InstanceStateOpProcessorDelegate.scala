package mesosphere.marathon
package core.task.tracker.impl
//scalastyle:off
import java.time.Clock

import akka.Done
import akka.actor.ActorRef
import akka.util.Timeout
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.task.tracker.impl.InstanceTrackerActor.ForwardTaskOp
import mesosphere.marathon.core.task.tracker.{ InstanceStateOpProcessor, InstanceTrackerConfig }
import mesosphere.marathon.state.Timestamp
import org.apache.mesos

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

  import scala.concurrent.ExecutionContext.Implicits.global

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

  override def launchEphemeral(instance: Instance): Future[Done] = {
    process(InstanceUpdateOperation.LaunchEphemeral(instance)).map(_ => Done)
  }

  override def revert(instance: Instance): Future[Done] = {
    process(InstanceUpdateOperation.Revert(instance)).map(_ => Done)
  }

  override def forceExpunge(instanceId: Instance.Id): Future[Done] = {
    process(InstanceUpdateOperation.ForceExpunge(instanceId)).map(_ => Done)
  }

  override def updateStatus(instance: Instance, mesosStatus: mesos.Protos.TaskStatus, updateTime: Timestamp): Future[Done] = {
    process(InstanceUpdateOperation.MesosUpdate(instance, mesosStatus, updateTime)).map(_ => Done)
  }

  override def updateReservationTimeout(instanceId: Instance.Id): Future[Done] = {
    process(InstanceUpdateOperation.ReservationTimeout(instanceId)).map(_ => Done)
  }
}
