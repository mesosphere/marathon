package mesosphere.marathon.core.task.tracker.impl

import java.util.concurrent.TimeoutException

import akka.actor.ActorRef
import akka.pattern.AskTimeoutException
import akka.util.Timeout
import mesosphere.marathon.Protos.MarathonTask
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

  override def list: TaskTracker.AppDataMap = {
    import ExecutionContext.Implicits.global
    Await.result(listAsync(), taskTrackerQueryTimeout.duration)

  }
  override def listAsync()(implicit ec: ExecutionContext): Future[TaskTracker.AppDataMap] = {
    import akka.pattern.ask
    def futureCall(): Future[TaskTracker.AppDataMap] =
      (taskTrackerRef ? TaskTrackerActor.List).mapTo[TaskTracker.AppDataMap].recover {
        case e: AskTimeoutException =>
          throw new TimeoutException(
            s"timeout while calling list. If you know what you are doing, you can adjust the timeout " +
              s"with --${config.internalTaskTrackerRequestTimeout.name}."
          )
      }
    listAsyncTimer.fold(futureCall())(_.timeFuture(futureCall()))
  }

  override def count(appId: PathId): Int = list.getTasks(appId).size
  override def countAsync(appId: PathId)(implicit ec: ExecutionContext): Future[Int] =
    listAsync().map(_.getTasks(appId).size)
  override def getTask(appId: PathId, taskId: String): Option[MarathonTask] = list.getTask(appId, taskId)
  override def getTaskAsync(appId: PathId, taskId: String)(implicit e: ExecutionContext): Future[Option[MarathonTask]] =
    listAsync().map(_.getTask(appId, taskId))
  override def contains(appId: PathId): Boolean = list.appTasks.contains(appId)
  override def containsAsync(appId: PathId)(implicit ec: ExecutionContext): Future[Boolean] =
    listAsync().map(_.appTasks.contains(appId))
  override def getTasks(appId: PathId): Iterable[MarathonTask] = list.getTasks(appId)
  override def getTasksAsync(appId: PathId)(implicit ec: ExecutionContext): Future[Iterable[MarathonTask]] =
    listAsync().map(_.getTasks(appId))

  private[this] val listAsyncTimer =
    metrics.map(metrics => metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "list")))

  private[this] implicit val taskTrackerQueryTimeout: Timeout = config.internalTaskTrackerRequestTimeout().milliseconds

}
