package mesosphere.marathon.core.task.tracker.impl

import akka.actor.ActorRef
import akka.util.Timeout
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.TaskStateOp.ReservationTimeout
import mesosphere.marathon.core.task.{ TaskStateChange, TaskStateOp, Task }
import mesosphere.marathon.core.task.tracker.impl.TaskTrackerActor.ForwardTaskOp
import mesosphere.marathon.core.task.tracker.{
  TaskReservationTimeoutHandler,
  TaskStateOpProcessor,
  TaskCreationHandler,
  TaskTrackerConfig
}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * Implements the [[TaskStateOpProcessor]] trait by sending messages to the TaskTracker actor.
  */
private[tracker] class TaskCreationHandlerAndUpdaterDelegate(
  clock: Clock,
  conf: TaskTrackerConfig,
  taskTrackerRef: ActorRef)
    extends TaskCreationHandler with TaskStateOpProcessor with TaskReservationTimeoutHandler {

  import scala.concurrent.ExecutionContext.Implicits.global

  private[impl] implicit val timeout: Timeout = conf.internalTaskUpdateRequestTimeout().milliseconds

  override def process(stateOp: TaskStateOp): Future[TaskStateChange] = {
    taskUpdate(stateOp.taskId, stateOp)
  }

  override def created(taskStateOp: TaskStateOp): Future[Unit] = {
    process(taskStateOp).map(_ => ())
  }
  override def terminated(stateOp: TaskStateOp.ForceExpunge): Future[_] = {
    process(stateOp)
  }
  override def timeout(stateOp: ReservationTimeout): Future[_] = {
    process(stateOp)
  }

  private[this] def taskUpdate(taskId: Task.Id, taskStateOp: TaskStateOp): Future[TaskStateChange] = {
    import akka.pattern.ask
    val deadline = clock.now + timeout.duration
    val op: ForwardTaskOp = TaskTrackerActor.ForwardTaskOp(deadline, taskId, taskStateOp)
    (taskTrackerRef ? op).mapTo[TaskStateChange].recover {
      case NonFatal(e) =>
        throw new RuntimeException(s"while asking for $taskStateOp on app [${taskId.appId}] and $taskId", e)
    }
  }
}
