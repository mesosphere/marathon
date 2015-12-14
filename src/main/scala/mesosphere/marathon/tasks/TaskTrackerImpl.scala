package mesosphere.marathon.tasks

import javax.inject.{ Singleton, Inject }

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.Protos._
import mesosphere.marathon.state.{ PathId, TaskRepository, Timestamp }
import org.apache.mesos.Protos.TaskState._
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.slf4j.LoggerFactory

import scala.collection._
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Set
import scala.concurrent.{ Await, Future }

/**
  * The TaskTracker contains the latest known state for every task.
  *
  * It also processes new status information as sent by Mesos (see taskUpdate)
  * and persist it in the associated task state.
  *
  * Known deficiencies:
  *
  * * There are race conditions in this code involving the synchronization of the repo and the cachedApps
  *   data structure.
  * * There are probably race conditions in checking whether to remove the entry for an app.
  * * Some methods such as list only look into the cached tasks. If the first reconciliation has not yet
  *   happened, this might not contain all apps. Could be fixed by preloading all tasks on election.
  * * Some methods or data structures use Set[MarathonTask] which leads to performance problems because
  *   hashCode on MarathonTask is relatively expensive.
  */
class TaskTrackerImpl @Inject() (
    repo: TaskRepository,
    config: MarathonConf) extends TaskCreator with TaskUpdater with TaskReconciler {

  import mesosphere.marathon.tasks.TaskTrackerImpl._
  import mesosphere.util.ThreadPoolContext.context

  implicit val timeout = config.zkTimeoutDuration

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  private[tasks] val cachedApps = TrieMap[PathId, InternalApp]()

  override def getTasks(appId: PathId): Iterable[MarathonTask] = {
    getTaskMap(appId).values
  }

  override def getTask(appId: PathId, taskId: String): Option[MarathonTask] = getTaskMap(appId).get(taskId)

  override def getVersion(appId: PathId, taskId: String): Option[Timestamp] =
    getTask(appId, taskId).map(task => Timestamp(task.getVersion))

  private[this] def getTaskMap(appId: PathId): TrieMap[String, MarathonTask] =
    getInternalApp(appId).tasks

  private[this] def getInternalApp(appId: PathId): InternalApp = {
    cachedApps.getOrElseUpdate(appId, fetchApp(appId))
  }

  // FIXME: remove, see documented deficiencies above
  override def list: Map[PathId, TaskTracker.App] = cachedApps.mapValues(_.toApp).toMap

  override def count(appId: PathId): Int = getTaskMap(appId).size

  // FIXME: remove, see documented deficiencies above
  override def contains(appId: PathId): Boolean = cachedApps.contains(appId)

  /**
    * Create a new task and store it in-memory and in the underlying storage.
    *
    * If the task exists already, the returned Future will fail.
    */
  override def created(appId: PathId, task: MarathonTask): Future[MarathonTask] = {
    val taskId = task.getId
    getTaskMap(appId).putIfAbsent(task.getId, task) match {
      case Some(existingTask) =>
        Future.failed(new IllegalStateException(s"cannot create task [$taskId] of app [$appId], it already exists"))
      case None =>
        repo.store(task).map { task =>
          log.debug(s"created task [$taskId] of app [$appId]")
          task
        }
    }
  }

  /**
    * Process a status update for an existing task and either updates the tasks or removes
    * it from the TaskTracker.
    *
    * If the task does not exist yet, the returned Future will fail.
    */
  override def statusUpdate(appId: PathId, status: TaskStatus): Future[_] = {
    val taskId = status.getTaskId.getValue
    val app = getInternalApp(appId)
    val maybeAppTask: Option[MarathonTask] = app.tasks.get(taskId)

    maybeAppTask match {
      case Some(task) =>
        processUpdate(app, task, status)
      case None =>
        Future.failed(new IllegalStateException(s"task [$taskId] of app [$appId] does not exist"))
    }
  }

  private[this] def processUpdate(app: InternalApp, task: MarathonTask, status: TaskStatus): Future[_] = {
    def updateTaskOnStateChange(task: MarathonTask): Future[_] = {
      def statusDidChange(statusA: TaskStatus, statusB: TaskStatus): Boolean = {
        val healthy = statusB.hasHealthy &&
          (!statusA.hasHealthy || statusA.getHealthy != statusB.getHealthy)

        healthy || statusA.getState != statusB.getState
      }

      if (statusDidChange(task.getStatus, status)) {
        updateTask(task.toBuilder.setStatus(status).build)
      }
      else {
        log.debug(s"Ignoring status update for ${task.getId}. Status did not change.")
        Future.successful(())
      }
    }

    def updateTask(updatedTask: MarathonTask): Future[_] = {
      val appId = app.appName
      val taskId: String = updatedTask.getId
      log.debug(s"updating task [$taskId] of app [$appId]: {}", updatedTask)

      getTaskMap(appId) += (taskId -> updatedTask)
      repo.store(updatedTask).map(_ => updatedTask)
    }

    status.getState match {
      case TASK_ERROR | TASK_FAILED | TASK_FINISHED | TASK_KILLED | TASK_LOST =>
        removeTask(app, task.getId)
        Future.successful(())
      case TASK_RUNNING if !task.hasStartedAt => // was staged, is now running
        updateTask(task.toBuilder.setStartedAt(System.currentTimeMillis).setStatus(status).build())
      case _ =>
        updateTaskOnStateChange(task)
    }
  }

  /**
    * Remove the task for the given app with the given ID completely.
    *
    * If the task does not exist, the method will not fail.
    */
  override def terminated(appId: PathId, taskId: String): Future[_] = {
    val app = getInternalApp(appId)
    removeTask(app, taskId)
    Future.successful(())
  }

  private[this] def removeTask(app: InternalApp, taskId: String): Unit = {
    app.tasks.remove(taskId)

    // FIXME: Should use Future as method return value instead of Await
    Await.result(repo.expunge(taskId), timeout)

    log.info(s"Task $taskId expunged and removed from TaskTracker")

    if (app.shutdown && app.tasks.isEmpty) {
      // Are we shutting down this app? If so, remove it
      remove(app.appName)
    }
  }

  override def removeUnknownAppAndItsTasks(appId: PathId): Unit = {
    val app = getInternalApp(appId)
    app.shutdown = true
    if (app.tasks.isEmpty) remove(appId)
  }

  private[this] def remove(appId: PathId): Unit = {
    cachedApps.remove(appId)
    log.info(s"App $appId removed from TaskTracker")
  }

  // FIXME: See deficiencies above, should probably be replaced by preloading all tasks on elected
  private[tasks] def fetchApp(appId: PathId): InternalApp = {
    def fetchTask(taskId: String): Option[MarathonTask] = Await.result(repo.task(taskId), timeout)

    val names = Await.result(repo.allIds(), timeout).toSet
    val tasks = TrieMap[String, MarathonTask]()
    val taskKeys = names.filter(TaskIdUtil.taskBelongsTo(appId))
    for {
      taskKey <- taskKeys
      task <- fetchTask(taskKey)
    } tasks += (task.getId -> task)

    log.debug(s"Fetched app [$appId] from store with ${tasks.size} tasks")
    new InternalApp(appId, tasks, false)
  }

  /**
    * Clear the in-memory state of the task tracker.
    */
  def clearCache(): Unit = {
    log.info("Clearing cache")
    cachedApps.clear()
  }
}

object TaskTrackerImpl {

  private[marathon] class InternalApp(
      val appName: PathId,
      var tasks: TrieMap[String, MarathonTask],
      var shutdown: Boolean) {

    def toApp: TaskTracker.App = TaskTracker.App(appName, tasks.values.to[Vector], shutdown)
  }

}
