package mesosphere.marathon.core.task.tracker.impl

import akka.actor.ActorRef
import akka.util.Timeout
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.impl.TaskTrackerActor.ForwardTaskOp
import mesosphere.marathon.core.task.tracker.{ TaskCreationHandler, TaskTrackerConfig, TaskUpdater }
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
    extends TaskCreationHandler with TaskUpdater {

  import scala.concurrent.ExecutionContext.Implicits.global

  private[impl] implicit val timeout: Timeout = conf.internalTaskUpdateRequestTimeout().milliseconds

  override def created(appId: PathId, task: MarathonTask): Future[MarathonTask] = {
    val taskState = TaskSerializer.taskState(task)
    taskUpdate(appId, task.getId, TaskOpProcessor.Action.Update(taskState)).map(_ => task)
  }
  override def terminated(appId: PathId, taskId: String): Future[_] = {
    taskUpdate(appId, taskId, TaskOpProcessor.Action.Expunge)
  }
  override def statusUpdate(appId: PathId, status: TaskStatus): Future[_] = {
    taskUpdate(appId, status.getTaskId.getValue, TaskOpProcessor.Action.UpdateStatus(status))
  }

  private[this] def taskUpdate(appId: PathId, taskId: String, action: TaskOpProcessor.Action): Future[Unit] = {
    import akka.pattern.ask
    val deadline = clock.now + timeout.duration
    val op: ForwardTaskOp = TaskTrackerActor.ForwardTaskOp(deadline, appId, Task.Id(taskId), action)
    (taskTrackerRef ? op).mapTo[Unit].recover {
      case NonFatal(e) =>
        throw new RuntimeException(s"while asking for $action on app [$appId] and task [$taskId]", e)
    }
  }
}
