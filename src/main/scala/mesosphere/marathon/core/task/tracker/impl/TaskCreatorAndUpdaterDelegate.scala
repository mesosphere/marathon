package mesosphere.marathon.core.task.tracker.impl

import akka.actor.ActorRef
import akka.util.Timeout
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.{ TaskCreator, TaskTrackerConfig, TaskUpdater }
import mesosphere.marathon.state.PathId
import org.apache.mesos.Protos.TaskStatus

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * Implements the [[TaskCreator]]/[[TaskUpdater]] traits by sending messages to the TaskTracker actor.
  */
private[tracker] class TaskCreatorAndUpdaterDelegate(conf: TaskTrackerConfig, taskTrackerRef: ActorRef)
    extends TaskCreator with TaskUpdater {

  import scala.concurrent.ExecutionContext.Implicits.global

  override def created(appId: PathId, task: MarathonTask): Future[MarathonTask] = {
    taskUpdate(appId, task.getId, TaskOpProcessor.Action.Update(task)).map(_ => task)
  }
  override def terminated(appId: PathId, taskId: String): Future[_] = {
    taskUpdate(appId, taskId, TaskOpProcessor.Action.Expunge)
  }
  override def statusUpdate(appId: PathId, status: TaskStatus): Future[_] = {
    taskUpdate(appId, status.getTaskId.getValue, TaskOpProcessor.Action.UpdateStatus(status))
  }

  private[this] def taskUpdate(appId: PathId, taskId: String, action: TaskOpProcessor.Action): Future[Unit] = {
    import akka.pattern.ask
    implicit val timeout: Timeout = conf.taskUpdateRequestTimeout().milliseconds
    (taskTrackerRef ? TaskTrackerActor.ForwardTaskOp(appId, taskId, action)).mapTo[Unit].recover {
      case NonFatal(e) =>
        throw new RuntimeException(s"while asking for $action on app [$appId] and task [$taskId]", e)
    }
  }
}
