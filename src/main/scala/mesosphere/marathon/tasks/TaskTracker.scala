package mesosphere.marathon.tasks

import java.io._
import javax.inject.Inject

import mesosphere.marathon.MarathonConf
import mesosphere.marathon.Protos._
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ PathId, StateMetrics, Timestamp }
import mesosphere.util.state.{ PersistentEntity, PersistentStore }
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection._
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Set
import scala.concurrent.{ Await, Future }

class TaskTracker @Inject() (
    store: PersistentStore,
    config: MarathonConf,
    val metrics: Metrics) extends StateMetrics {

  import mesosphere.marathon.tasks.TaskTracker._
  import mesosphere.util.ThreadPoolContext.context

  implicit val timeout = config.zkTimeoutDuration

  private[this] val log = LoggerFactory.getLogger(getClass.getName)

  val PREFIX = "task:"
  val ID_DELIMITER = ":"

  private[tasks] val apps = TrieMap[PathId, InternalApp]()

  private[tasks] def fetchFromState(id: String): Option[PersistentEntity] = timedRead {
    Await.result(store.load(id), timeout)
  }

  private[tasks] def getKey(appId: PathId, taskId: String): String = {
    PREFIX + appId.safePath + ID_DELIMITER + taskId
  }

  def get(appId: PathId): Set[MarathonTask] =
    getTasks(appId).toSet

  def getTasks(appId: PathId): Iterable[MarathonTask] = {
    getInternal(appId).values
  }

  def getVersion(appId: PathId, taskId: String): Option[Timestamp] =
    get(appId).collectFirst {
      case mt: MarathonTask if mt.getId == taskId =>
        Timestamp(mt.getVersion)
    }

  private[this] def getInternal(appId: PathId): TrieMap[String, MarathonTask] =
    apps.getOrElseUpdate(appId, fetchApp(appId)).tasks

  def list: Map[PathId, App] = apps.mapValues(_.toApp).toMap

  def count(appId: PathId): Int = getInternal(appId).size

  def contains(appId: PathId): Boolean = apps.contains(appId)

  def take(appId: PathId, n: Int): Set[MarathonTask] = getTasks(appId).iterator.take(n).toSet

  def created(appId: PathId, task: MarathonTask): Unit = {
    // Keep this here so running() can pick it up
    // FIXME: Should be persisted here for task reconciliation
    //        Wont fix for now since this should be completely remodeled in #462
    getInternal(appId) += (task.getId -> task)
  }

  def running(appId: PathId, status: TaskStatus): Future[MarathonTask] = {
    val taskId = status.getTaskId.getValue
    getInternal(appId).get(taskId) match {
      case Some(oldTask) if !oldTask.hasStartedAt => // staged
        val task = oldTask.toBuilder
          .setStartedAt(System.currentTimeMillis)
          .setStatus(status)
          .build
        getInternal(appId) += (task.getId -> task)
        store(appId, task).map(_ => task)

      case Some(oldTask) => // running
        val msg = s"Task for ID $taskId already running, ignoring"
        log.warn(msg)
        Future.failed(new Exception(msg))

      case _ =>
        val msg = s"No staged task for ID $taskId, ignoring"
        log.warn(msg)
        Future.failed(new Exception(msg))
    }
  }

  def terminated(appId: PathId, taskId: String): Future[Option[MarathonTask]] = {
    val appTasks = getInternal(appId)
    val app = apps(appId)

    appTasks.get(taskId) match {
      case Some(task) =>
        app.tasks.remove(task.getId)

        timedWrite {
          Await.result(store.delete(getKey(appId, taskId)), timeout)
        }

        log.info(s"Task $taskId expunged and removed from TaskTracker")

        if (app.shutdown && app.tasks.isEmpty) {
          // Are we shutting down this app? If so, remove it
          remove(appId)
        }

        Future.successful(Some(task))
      case None =>
        if (app.shutdown && app.tasks.isEmpty) {
          // Are we shutting down this app? If so, remove it
          remove(appId)
        }
        Future.successful(None)
    }
  }

  def shutdown(appId: PathId): Unit = {
    apps.getOrElseUpdate(appId, fetchApp(appId)).shutdown = true
    if (apps(appId).tasks.isEmpty) remove(appId)
  }

  private[this] def remove(appId: PathId): Unit = {
    apps.remove(appId)
    log.warn(s"App $appId removed from TaskTracker")
  }

  def statusUpdate(appId: PathId, status: TaskStatus): Future[Option[MarathonTask]] = {
    val taskId = status.getTaskId.getValue
    getInternal(appId).get(taskId) match {
      case Some(task) if statusDidChange(task.getStatus, status) =>
        val updatedTask = task.toBuilder
          .setStatus(status)
          .build
        getInternal(appId) += (task.getId -> updatedTask)
        store(appId, updatedTask).map(_ => Some(updatedTask))

      case Some(task) =>
        log.debug(s"Ignoring status update for ${task.getId}. Status did not change.")
        Future.successful(Some(task))

      case _ =>
        log.warn(s"No task for ID $taskId")
        Future.successful(None)
    }
  }

  def determineOverdueTasks(now: Timestamp): Iterable[MarathonTask] = {

    val nowMillis = now.toDateTime.getMillis
    // stagedAt is set when the task is created by the scheduler
    val stagedExpire = nowMillis - config.taskLaunchTimeout()
    val unconfirmedExpire = nowMillis - config.taskLaunchConfirmTimeout()

    val toKill = apps.values.iterator.flatMap(_.tasks.values).filter { task =>
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

  def expungeOrphanedTasks(): Unit = {
    // Remove tasks that don't have any tasks associated with them. Expensive!
    log.info("Expunging orphaned tasks from store")
    val stateTaskKeys = timedRead {
      Await.result(store.allIds(), timeout).filter(_.startsWith(PREFIX))
    }
    val appsTaskKeys = apps.values.flatMap { app =>
      app.tasks.keys.map(taskId => getKey(app.appName, taskId))
    }.toSet

    for (stateTaskKey <- stateTaskKeys) {
      if (!appsTaskKeys.contains(stateTaskKey)) {
        log.info(s"Expunging orphaned task with key $stateTaskKey")
        timedWrite {
          Await.result(store.delete(stateTaskKey), timeout)
        }
      }
    }
  }

  private[tasks] def fetchApp(appId: PathId): InternalApp = {
    log.debug(s"Fetching app from store $appId")
    val names = timedRead {
      Await.result(store.allIds(), timeout).toSet
    }
    val tasks = TrieMap[String, MarathonTask]()
    val taskKeys = names.filter(name => name.startsWith(PREFIX + appId.safePath + ID_DELIMITER))
    for {
      taskKey <- taskKeys
      task <- fetchTask(taskKey)
    } tasks += (task.getId -> task)

    new InternalApp(appId, tasks, false)
  }

  def fetchTask(appId: PathId, taskId: String): Option[MarathonTask] =
    fetchTask(getKey(appId, taskId))

  private[tasks] def fetchTask(taskKey: String): Option[MarathonTask] = {
    fetchFromState(taskKey).flatMap { entity =>
      val source = new ObjectInputStream(new ByteArrayInputStream(entity.bytes.toArray))
      deserialize(taskKey, source)
    }
  }

  def deserialize(taskKey: String, source: ObjectInputStream): Option[MarathonTask] = {
    if (source.available > 0) {
      try {
        val size = source.readInt
        val bytes = new Array[Byte](size)
        source.readFully(bytes)
        Some(MarathonTask.parseFrom(bytes))
      }
      catch {
        case e: com.google.protobuf.InvalidProtocolBufferException =>
          log.warn(s"Unable to deserialize task state for $taskKey", e)
          None
      }
    }
    else {
      log.warn(s"Unable to deserialize task state for $taskKey")
      None
    }
  }

  def legacyDeserialize(appId: PathId, source: ObjectInputStream): TrieMap[String, MarathonTask] = {
    var results = TrieMap[String, MarathonTask]()

    if (source.available > 0) {
      try {
        val size = source.readInt
        val bytes = new Array[Byte](size)
        source.readFully(bytes)
        val app = MarathonApp.parseFrom(bytes)
        if (app.getName != appId.toString) {
          log.warn(s"App name from task state for $appId is wrong!  Got '${app.getName}' Continuing anyway...")
        }
        results ++= app.getTasksList.asScala.map(x => x.getId -> x)
      }
      catch {
        case e: com.google.protobuf.InvalidProtocolBufferException =>
          log.warn(s"Unable to deserialize task state for $appId", e)
      }
    }
    else {
      log.warn(s"Unable to deserialize task state for $appId")
    }
    results
  }

  def serialize(task: MarathonTask, sink: ObjectOutputStream): Unit = {
    val size = task.getSerializedSize
    sink.writeInt(size)
    sink.write(task.toByteArray)
    sink.flush()
  }

  def store(appId: PathId, task: MarathonTask): Future[PersistentEntity] = {
    val byteStream = new ByteArrayOutputStream()
    val output = new ObjectOutputStream(byteStream)
    serialize(task, output)
    val bytes = byteStream.toByteArray
    val key: String = getKey(appId, task.getId)
    timedWrite(fetchFromState(key) match {
      case Some(entity) => store.update(entity.withNewContent(bytes))
      case None         => store.create(key, bytes)
    })
  }

  /**
    * Clear the in-memory state of the task tracker.
    */
  def clear(): Unit = apps.clear()

  private[tasks] def statusDidChange(statusA: TaskStatus, statusB: TaskStatus): Boolean = {
    val healthy = statusB.hasHealthy &&
      (!statusA.hasHealthy || statusA.getHealthy != statusB.getHealthy)

    healthy || statusA.getState != statusB.getState
  }
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
