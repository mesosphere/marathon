package mesosphere.marathon.core.task.tracker.impl

import akka.actor.ActorRef
import akka.util.Timeout
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.bus.MarathonTaskStatus
import mesosphere.marathon.core.task.{ TaskStateChange, TaskStateOp, Task }
import mesosphere.marathon.core.task.tracker.impl.TaskTrackerActor.ForwardTaskOp
import mesosphere.marathon.core.task.tracker.{
  TaskStateOpProcessor,
  TaskCreationHandler,
  TaskTrackerConfig,
  TaskUpdater
}
import mesosphere.marathon.state.PathId
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * Implements the [[TaskCreationHandler]]/[[TaskUpdater]] traits by sending messages to the TaskTracker actor.
  */
private[tracker] class TaskCreationHandlerAndUpdaterDelegate(
  clock: Clock,
  conf: TaskTrackerConfig,
  taskTrackerRef: ActorRef)
    extends TaskCreationHandler with TaskUpdater with TaskStateOpProcessor {

  import scala.concurrent.ExecutionContext.Implicits.global

  private[impl] implicit val timeout: Timeout = conf.internalTaskUpdateRequestTimeout().milliseconds

  override def process(stateOp: TaskStateOp): Future[Unit] = {
    taskUpdate(stateOp.taskId, stateOp).map(_ => ())
  }

  // FIXME (3221): remove created, terminated, statusUpdate
  override def created(taskStateOp: TaskStateOp): Future[Unit] = {
    taskUpdate(taskStateOp.taskId, taskStateOp).map(_ => ())
  }
  override def terminated(stateOp: TaskStateOp.ForceExpunge): Future[_] = {
    taskUpdate(stateOp.taskId, stateOp)
  }
  override def statusUpdate(appId: PathId, status: TaskStatus): Future[_] = {
    val taskId = Task.Id(status.getTaskId.getValue)
    val stateOp = TaskStateOp.MesosUpdate(taskId, MarathonTaskStatus(status), clock.now())
    taskUpdate(taskId, stateOp)
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
