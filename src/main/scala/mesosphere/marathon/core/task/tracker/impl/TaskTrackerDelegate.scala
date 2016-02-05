package mesosphere.marathon.core.task.tracker.impl

import java.util.concurrent.TimeoutException

import akka.actor.ActorRef
import akka.pattern.AskTimeoutException
import akka.util.Timeout
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.{ TaskTracker, TaskTrackerConfig }
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.state.PathId

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
  * Provides a [[TaskTracker]] interface to [[TaskTrackerActor]].
  *
  * This is used for the "global" TaskTracker trait and it is also
  * is used internally in this package to communicate with the TaskTracker.
  *
  * @param metrics a metrics object if we want to track metrics for this delegate. We only want to track
  *                metrics for the "global" TaskTracker.
  */
private[tracker] class TaskTrackerDelegate(
    metrics: Option[Metrics],
    config: TaskTrackerConfig,
    taskTrackerRef: ActorRef) extends TaskTracker {

  override def tasksByAppSync: TaskTracker.TasksByApp = {
    import ExecutionContext.Implicits.global
    Await.result(tasksByApp(), taskTrackerQueryTimeout.duration)

  }
  override def tasksByApp()(implicit ec: ExecutionContext): Future[TaskTracker.TasksByApp] = {
    import akka.pattern.ask
    def futureCall(): Future[TaskTracker.TasksByApp] =
      (taskTrackerRef ? TaskTrackerActor.List).mapTo[TaskTracker.TasksByApp].recover {
        case e: AskTimeoutException =>
          throw new TimeoutException(
            s"timeout while calling list. If you know what you are doing, you can adjust the timeout " +
              s"with --${config.internalTaskTrackerRequestTimeout.name}."
          )
      }
    tasksByAppTimer.fold(futureCall())(_.timeFuture(futureCall()))
  }

  override def countAppTasksSync(appId: PathId): Int = tasksByAppSync.appTasks(appId).size
  override def countAppTasks(appId: PathId)(implicit ec: ExecutionContext): Future[Int] =
    tasksByApp().map(_.appTasks(appId).size)
  override def marathonTaskSync(appId: PathId, taskId: String): Option[MarathonTask] =
    tasksByAppSync.marathonTask(appId, taskId)
  override def marathonTask(appId: PathId, taskId: String)(implicit e: ExecutionContext): Future[Option[MarathonTask]] =
    tasksByApp().map(_.marathonTask(appId, taskId))
  override def hasAppTasksSync(appId: PathId): Boolean = tasksByAppSync.hasAppTasks(appId)
  override def hasAppTasks(appId: PathId)(implicit ec: ExecutionContext): Future[Boolean] =
    tasksByApp().map(_.hasAppTasks(appId))
  override def appTasksSync(appId: PathId): Iterable[MarathonTask] = tasksByAppSync.appTasks(appId)
  override def appTasks(appId: PathId)(implicit ec: ExecutionContext): Future[Iterable[MarathonTask]] =
    tasksByApp().map(_.appTasks(appId))

  override def task(
    appId: PathId, taskId: Task.Id)(
      implicit ec: ExecutionContext): Future[Option[Task]] =
    tasksByApp().map(_.taskState(appId, taskId))

  private[this] val tasksByAppTimer =
    metrics.map(metrics => metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "tasksByApp")))

  private[this] implicit val taskTrackerQueryTimeout: Timeout = config.internalTaskTrackerRequestTimeout().milliseconds

}
