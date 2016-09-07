package mesosphere.marathon.core.task.tracker.impl

import akka.actor.ActorRef
import akka.util.Timeout
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.instance.{ Instance, InstanceStateOp }
import mesosphere.marathon.core.task.TaskStateOp.ReservationTimeout
import mesosphere.marathon.core.task.{ TaskStateChange }
import mesosphere.marathon.core.task.tracker.impl.InstanceTrackerActor.ForwardInstanceOp
import mesosphere.marathon.core.task.tracker._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * Implements the [[TaskStateOpProcessor]] trait by sending messages to the TaskTracker actor.
  */
private[tracker] class TaskCreationHandlerAndUpdaterDelegate(
  clock: Clock,
  conf: InstanceTrackerConfig,
  taskTrackerRef: ActorRef)
    extends InstanceCreationHandler with TaskStateOpProcessor with TaskReservationTimeoutHandler {

  import scala.concurrent.ExecutionContext.Implicits.global

  private[impl] implicit val timeout: Timeout = conf.internalTaskUpdateRequestTimeout().milliseconds

  override def process(stateOp: InstanceStateOp): Future[TaskStateChange] = {
    taskUpdate(stateOp.instanceId, stateOp)
  }

  override def created(taskStateOp: InstanceStateOp): Future[Unit] = {
    process(taskStateOp).map(_ => ())
  }
  override def terminated(stateOp: InstanceStateOp.ForceExpunge): Future[_] = {
    process(stateOp)
  }
  override def timeout(stateOp: ReservationTimeout): Future[_] = {
    process(stateOp)
  }

  private[this] def taskUpdate(taskId: Instance.Id, instanceStateOp: InstanceStateOp): Future[TaskStateChange] = {
    import akka.pattern.ask
    val deadline = clock.now + timeout.duration
    val op: ForwardInstanceOp = InstanceTrackerActor.ForwardInstanceOp(deadline, taskId, instanceStateOp)
    (taskTrackerRef ? op).mapTo[TaskStateChange].recover {
      case NonFatal(e) =>
        throw new RuntimeException(s"while asking for $instanceStateOp on app [${taskId.runSpecId}] and $taskId", e)
    }
  }
}
