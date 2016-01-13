package mesosphere.marathon.core.task.tracker.impl

import akka.actor.ActorRef
import akka.util.Timeout
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.{ TaskTrackerConfig, TaskTracker }
import mesosphere.marathon.metrics.{ MetricPrefixes, Metrics }
import mesosphere.marathon.state.PathId
import TaskTracker.App

import scala.collection.Map
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Await, Future }

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

  override def list: Map[PathId, App] = appDataMapSync.toTaskTrackerAppMap
  override def listAsync()(implicit ec: ExecutionContext): Future[Map[PathId, App]] =
    appDataMapFuture.map(_.toTaskTrackerAppMap)
  override def count(appId: PathId): Int = appDataMapSync.getTasks(appId).size
  override def countAsync(appId: PathId)(implicit ec: ExecutionContext): Future[Int] =
    appDataMapFuture.map(_.getTasks(appId).size)
  override def getTask(appId: PathId, taskId: String): Option[MarathonTask] = appDataMapSync.getTask(appId, taskId)
  override def getTaskAsync(appId: PathId, taskId: String)(implicit e: ExecutionContext): Future[Option[MarathonTask]] =
    appDataMapFuture.map(_.getTask(appId, taskId))
  override def contains(appId: PathId): Boolean = appDataMapSync.appTasks.contains(appId)
  override def containsAsync(appId: PathId)(implicit ec: ExecutionContext): Future[Boolean] =
    appDataMapFuture.map(_.appTasks.contains(appId))
  override def getTasks(appId: PathId): Iterable[MarathonTask] = appDataMapSync.getTasks(appId)
  override def getTasksAsync(appId: PathId)(implicit ec: ExecutionContext): Future[Iterable[MarathonTask]] =
    appDataMapFuture.map(_.getTasks(appId))

  private[this] val appDataMapFutureTimer =
    metrics.map(metrics => metrics.timer(metrics.name(MetricPrefixes.SERVICE, getClass, "appDataMapFuture")))

  private[this] implicit val taskTrackerQueryTimeout: Timeout = config.internalTaskTrackerRequestTimeout().milliseconds

  private[this] def appDataMapSync: AppDataMap = {
    Await.result(appDataMapFuture, taskTrackerQueryTimeout.duration)
  }

  private[impl] def appDataMapFuture: Future[AppDataMap] = {
    import akka.pattern.ask
    def futureCall(): Future[AppDataMap] = (taskTrackerRef ? TaskTrackerActor.List).mapTo[AppDataMap]
    appDataMapFutureTimer.fold(futureCall())(_.timeFuture(futureCall()))
  }
}
