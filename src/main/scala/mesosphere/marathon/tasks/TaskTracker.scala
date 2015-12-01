package mesosphere.marathon.tasks

import javax.inject.Inject

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
  * * expungeOrphanedTasks is probably not a good idea. See issue #2620.
  * * Some methods or data structures use Set[MarathonTask] which leads to performance problems because
  *   hashCode on MarathonTask is relatively expensive.
  */
class TaskTracker @Inject() (
    repo: TaskRepository,
    config: MarathonConf) {

  import mesosphere.marathon.tasks.TaskTracker._
  import mesosphere.util.ThreadPoolContext.context

  implicit val timeout = config.zkTimeoutDuration

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  private[tasks] val cachedApps = TrieMap[PathId, InternalApp]()

  // FIXME: remove, see documented deficiencies above
  def get(appId: PathId): Set[MarathonTask] =
    getTasks(appId).toSet

  def getTasks(appId: PathId): Iterable[MarathonTask] = {
    getTaskMap(appId).values
  }

  def getVersion(appId: PathId, taskId: String): Option[Timestamp] =
    get(appId).collectFirst {
      case mt: MarathonTask if mt.getId == taskId =>
        Timestamp(mt.getVersion)
    }

  private[this] def getTaskMap(appId: PathId): TrieMap[String, MarathonTask] =
    getInternalApp(appId).tasks

  private[this] def getInternalApp(appId: PathId): InternalApp = {
    cachedApps.getOrElseUpdate(appId, fetchApp(appId))
  }

  // FIXME: remove, see documented deficiencies above
  def list: Map[PathId, App] = cachedApps.mapValues(_.toApp).toMap

  def count(appId: PathId): Int = getTaskMap(appId).size

  // FIXME: remove, see documented deficiencies above
  def contains(appId: PathId): Boolean = cachedApps.contains(appId)

  def take(appId: PathId, n: Int): Set[MarathonTask] = getTasks(appId).iterator.take(n).toSet

  /**
    * Create a new task and store it in-memory and in the underlying storage.
    *
    * If the task exists already, the returned Future will fail.
    */
  def created(appId: PathId, task: MarathonTask): Future[MarathonTask] = {
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
  def statusUpdate(appId: PathId, status: TaskStatus): Future[_] = {
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
        val builder = task.toBuilder
        builder.setStartedAt(System.currentTimeMillis).setStatus(status)
        builder.setStatus(status)
        if (status.hasContainerStatus) builder.addAllNetworks(status.getContainerStatus.getNetworkInfosList)
        updateTask(builder.build())
      case _ =>
        updateTaskOnStateChange(task)
    }
  }

  /**
    * Remove the task for the given app with the given ID completely.
    *
    * If the task does not exist, the method will not fail.
    */
  def terminated(appId: PathId, taskId: String): Future[_] = {
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

  def shutdown(appId: PathId): Unit = {
    val app = getInternalApp(appId)
    app.shutdown = true
    if (app.tasks.isEmpty) remove(appId)
  }

  private[this] def remove(appId: PathId): Unit = {
    cachedApps.remove(appId)
    log.info(s"App $appId removed from TaskTracker")
  }

  def determineOverdueTasks(now: Timestamp): Iterable[MarathonTask] = {

    val nowMillis = now.toDateTime.getMillis
    // stagedAt is set when the task is created by the scheduler
    val stagedExpire = nowMillis - config.taskLaunchTimeout()
    val unconfirmedExpire = nowMillis - config.taskLaunchConfirmTimeout()

    val toKill = cachedApps.values.iterator.flatMap(_.tasks.values).filter { task =>
      /*
     * One would think that !hasStagedAt would be better for querying these tasks. However, the current implementation
     * of [[MarathonTasks.makeTask]] will set stagedAt to a non-zero value close to the current system time. Therefore,
     * all tasks will return a non-zero value for stagedAt so that we cannot use that.
     *
     * If, for some reason, a task was created (sent to mesos), but we never received a [[TaskStatus]] update event,
     * the task will also be killed after reaching the configured maximum.
     */
      if (task.getStartedAt != 0) {
        false
      }
      else if (task.hasStatus && task.getStatus.getState == TaskState.TASK_STAGING && task.getStagedAt < stagedExpire) {
        log.warn(s"Should kill: Task '${task.getId}' was staged ${(nowMillis - task.getStagedAt) / 1000}s" +
          s" ago and has not yet started")
        true
      }
      else if (task.getStagedAt < unconfirmedExpire) {
        log.warn(s"Should kill: Task '${task.getId}' was launched ${(nowMillis - task.getStagedAt) / 1000}s ago " +
          s"and was not confirmed yet"
        )
        true
      }
      else false
    }

    toKill.toIterable
  }

  // FIXME: Should be removed, see deficiencies above and #2620
  def expungeOrphanedTasks(): Unit = {
    // Remove tasks that don't have any tasks associated with them. Expensive!
    log.info("Expunging orphaned tasks from store")
    val stateTaskKeys = Await.result(repo.allIds(), timeout)
    val appsTaskKeys = cachedApps.values.flatMap(_.tasks.keys).toSet

    for (stateTaskKey <- stateTaskKeys) {
      if (!appsTaskKeys.contains(stateTaskKey)) {
        log.info(s"Expunging orphaned task with key $stateTaskKey")
        Await.result(repo.expunge(stateTaskKey), timeout)
      }
    }
  }

  // FIXME: See deficiencies above, should probably be replaced by preloading all tasks on elected
  private[tasks] def fetchApp(appId: PathId): InternalApp = {
    log.debug(s"Fetching app from store $appId")
    val names = Await.result(repo.allIds(), timeout).toSet
    val tasks = TrieMap[String, MarathonTask]()
    val taskKeys = names.filter(TaskIdUtil.taskBelongsTo(appId))
    for {
      taskKey <- taskKeys
      task <- fetchTask(taskKey)
    } tasks += (task.getId -> task)

    new InternalApp(appId, tasks, false)
  }

  // FIXME: Should probably not go to the repo and expose a Future in the interface
  def fetchTask(taskId: String): Option[MarathonTask] = Await.result(repo.task(taskId), timeout)

  /**
    * Clear the in-memory state of the task tracker.
    */
  def clearCache(): Unit = cachedApps.clear()
}

object TaskTracker {

  private[marathon] class InternalApp(
      val appName: PathId,
      var tasks: TrieMap[String, MarathonTask],
      var shutdown: Boolean) {

    def toApp: App = App(appName, tasks.values.toSet, shutdown)
  }

  case class App(appName: PathId, tasks: Set[MarathonTask], shutdown: Boolean)

}
