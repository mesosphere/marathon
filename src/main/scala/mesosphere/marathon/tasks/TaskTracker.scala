package mesosphere.marathon.tasks

import java.io._
import javax.inject.Inject

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonConf
import mesosphere.marathon.Protos._
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ PathId, StateMetrics, Timestamp }
import mesosphere.util.state.{ PersistentEntity, PersistentStore }
import org.apache.log4j.Logger
import org.apache.mesos.Protos.TaskStatus

import scala.collection.JavaConverters._
import scala.collection._
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Set
import scala.concurrent.{ Await, Future }

class TaskTracker @Inject() (
  store: PersistentStore,
  config: MarathonConf,
  val metrics: Metrics)
    extends StateMetrics {

  import mesosphere.marathon.tasks.TaskTracker._
  import mesosphere.util.ThreadPoolContext.context

  implicit val timeout = config.zkTimeoutDuration

  private[this] val log = Logger.getLogger(getClass.getName)

  val PREFIX = "task:"
  val ID_DELIMITER = ":"

  private[this] val apps = TrieMap[PathId, InternalApp]()

  private[tasks] def fetchFromState(id: String): Option[PersistentEntity] = timedRead {
    Await.result(store.load(id), timeout)
  }

  private[tasks] def getKey(appId: PathId, taskId: String): String = {
    PREFIX + appId.safePath + ID_DELIMITER + taskId
  }

  def get(appId: PathId): Set[MarathonTask] =
    getInternal(appId).values.toSet

  def getVersion(appId: PathId, taskId: String): Option[Timestamp] =
    get(appId).collectFirst {
      case mt: MarathonTask if mt.getId == taskId =>
        Timestamp(mt.getVersion)
    }

  private def getInternal(appId: PathId): TrieMap[String, MarathonTask] =
    apps.getOrElseUpdate(appId, fetchApp(appId)).tasks

  def list: Map[PathId, App] = apps.mapValues(_.toApp).toMap

  def count(appId: PathId): Int = getInternal(appId).size

  def contains(appId: PathId): Boolean = apps.contains(appId)

  def take(appId: PathId, n: Int): Set[MarathonTask] = get(appId).take(n)

  def created(appId: PathId, task: MarathonTask): Unit = {
    // Keep this here so running() can pick it up
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

  def terminated(appId: PathId, status: TaskStatus): Future[Option[MarathonTask]] = {
    val appTasks = getInternal(appId)
    val app = apps(appId)
    val taskId = status.getTaskId.getValue

    appTasks.get(taskId) match {
      case Some(task) =>
        app.tasks.remove(task.getId)

        timedWrite { Await.result(store.delete(getKey(appId, taskId)), timeout) }

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

  def stagedTasks(): Iterable[MarathonTask] = apps.values.flatMap(_.tasks.values.filter(_.getStartedAt == 0))

  def checkStagedTasks: Iterable[MarathonTask] = {
    // stagedAt is set when the task is created by the scheduler
    val now = System.currentTimeMillis
    val expires = now - config.taskLaunchTimeout()
    val toKill = stagedTasks().filter(_.getStagedAt < expires)

    toKill.foreach(t => {
      log.warn(s"Task '${t.getId}' was staged ${(now - t.getStagedAt) / 1000}s ago and has not yet started")
    })
    toKill
  }

  def expungeOrphanedTasks(): Unit = {
    // Remove tasks that don't have any tasks associated with them. Expensive!
    log.info("Expunging orphaned tasks from store")
    val stateTaskKeys = timedRead { Await.result(store.allIds(), timeout).filter(_.startsWith(PREFIX)) }
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
    val names = timedRead { Await.result(store.allIds(), timeout).toSet }
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
