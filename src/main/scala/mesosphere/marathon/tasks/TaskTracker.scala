package mesosphere.marathon.tasks

import java.io._
import javax.inject.Inject

import mesosphere.marathon.Protos._
import mesosphere.marathon.{ Main, MarathonConf }
import org.apache.log4j.Logger
import org.apache.mesos.Protos.TaskStatus
import org.apache.mesos.state.{ State, Variable }

import scala.collection.JavaConverters._
import scala.collection._
import scala.concurrent.Future

class TaskTracker @Inject() (state: State, config: MarathonConf) {

  import mesosphere.marathon.tasks.TaskTracker.App
  import mesosphere.util.BackToTheFuture.futureToFuture
  import mesosphere.util.ThreadPoolContext.context

  implicit val timeout = config.zkFutureTimeout

  private[this] val log = Logger.getLogger(getClass.getName)

  val LEGACY_PREFIX = "tasks:"
  val PREFIX = "task:"
  val ID_DELIMITER = ":"

  private[this] val apps = new mutable.HashMap[String, App] with mutable.SynchronizedMap[String, App]

  private[tasks] def fetchFromState(key: String) = state.fetch(key).get()

  private[tasks] def getLegacyKey(appName: String): String = {
    LEGACY_PREFIX + appName
  }

  private[tasks] def getKey(appName: String, taskId: String): String = {
    PREFIX + appName + ID_DELIMITER + taskId
  }

  def get(appName: String): mutable.Set[MarathonTask] =
    apps.getOrElseUpdate(appName, fetchApp(appName)).tasks

  def list: mutable.HashMap[String, App] = apps

  def count(appName: String): Int = get(appName).size

  def contains(appName: String): Boolean = apps.contains(appName)

  def take(appName: String, n: Int): Set[MarathonTask] = get(appName).take(n)

  def created(appName: String, task: MarathonTask): Unit = {
    // Keep this here so running() can pick it up
    get(appName) += task
  }

  def running(appName: String, status: TaskStatus): Future[MarathonTask] = {
    val taskId = status.getTaskId.getValue
    val task = get(appName).find(_.getId == taskId) match {
      case Some(stagedTask) =>
        get(appName).remove(stagedTask)
        stagedTask.toBuilder
          .setStartedAt(System.currentTimeMillis)
          .addStatuses(status)
          .build

      case _ =>
        log.warn(s"No staged task for ID ${taskId}")
        // We lost track of the host and port of this task, but still need to keep track of it
        MarathonTask.newBuilder
          .setId(taskId)
          .setStagedAt(System.currentTimeMillis)
          .setStartedAt(System.currentTimeMillis)
          .addStatuses(status)
          .build
    }
    get(appName) += task
    store(appName, task).map(_ => task)
  }

  def terminated(appName: String,
                 status: TaskStatus): Future[Option[MarathonTask]] = {
    val appTasks = get(appName)
    val app = apps(appName)
    val taskId = status.getTaskId.getValue

    appTasks.find(_.getId == taskId) match {
      case Some(task) =>
        app.tasks = appTasks - task

        val variable = fetchFromState(getKey(appName, taskId))
        state.expunge(variable)

        log.info(s"Task ${taskId} expunged and removed from TaskTracker")

        if (app.shutdown && app.tasks.isEmpty) {
          // Are we shutting down this app? If so, remove it
          remove(appName)
        }

        Future.successful(Some(task))
      case None =>
        if (app.shutdown && app.tasks.isEmpty) {
          // Are we shutting down this app? If so, remove it
          remove(appName)
        }
        Future.successful(None)
    }
  }

  def shutDown(appName: String): Unit = {
    apps.getOrElseUpdate(appName, fetchApp(appName)).shutdown = true
    if (apps(appName).tasks.isEmpty) remove(appName)
  }

  private[this] def remove(appName: String) {
    apps.remove(appName)
    log.warn(s"App ${appName} removed from TaskTracker")
  }

  def statusUpdate(appName: String,
                   status: TaskStatus): Future[Option[MarathonTask]] = {
    val taskId = status.getTaskId.getValue
    get(appName).find(_.getId == taskId) match {
      case Some(task) =>
        get(appName).remove(task)
        val updatedTask = task.toBuilder
          .addStatuses(status)
          .build
        get(appName) += updatedTask
        store(appName, updatedTask).map(_ => Some(updatedTask))

      case _ =>
        log.warn(s"No task for ID ${taskId}")
        Future.successful(None)
    }
  }

  def checkStagedTasks: Iterable[MarathonTask] = {
    // stagedAt is set when the task is created by the scheduler
    val now = System.currentTimeMillis
    val expires = now - Main.conf.taskLaunchTimeout()
    val toKill = apps.values.map { app =>
      app.tasks.filter(t => Option(t.getStartedAt).isEmpty && t.getStagedAt < expires)
    }.flatten

    toKill.foreach(t => {
      log.warn(s"Task '${t.getId}' was staged ${(now - t.getStagedAt) / 1000}s ago and has not yet started")
    })
    toKill
  }

  def expungeOrphanedTasks() {
    // Remove tasks that don't have any tasks associated with them. Expensive!
    log.info("Expunging orphaned tasks from store")
    val stateTaskKeys = state.names.get.asScala.filter(_.startsWith(PREFIX))
    val appsTaskKeys = apps.values.flatMap { app =>
      app.tasks.map(task => getKey(app.appName, task.getId))
    }.toSet

    for (stateTaskKey <- stateTaskKeys) {
      if (!appsTaskKeys.contains(stateTaskKey)) {
        log.info(s"Expunging orphaned task with key ${stateTaskKey}")
        val variable = state.fetch(stateTaskKey).get
        state.expunge(variable)
      }
    }
  }

  private[tasks] def fetchApp(appName: String): App = {
    log.debug(s"Fetching app from store ${appName}")
    val names = state.names().get.asScala.toSet
    if (names.exists(name => name.equals(LEGACY_PREFIX + appName))) {
      val tasks = migrateApp(appName)
      new App(appName, tasks, false)
    }
    else {
      val tasks: mutable.Set[MarathonTask] = new mutable.HashSet[MarathonTask]
      val taskKeys = names.filter(name => name.startsWith(PREFIX + appName + ID_DELIMITER))
      for (taskKey <- taskKeys) {
        fetchTask(taskKey) match {
          case Some(task) => tasks += task
          case None       => //no-op
        }
      }
      new App(appName, tasks, false)
    }
  }

  private[tasks] def fetchTask(taskKey: String): Option[MarathonTask] = {
    val bytes = fetchFromState(taskKey).value
    if (bytes.length > 0) {
      val source = new ObjectInputStream(new ByteArrayInputStream(bytes))
      deserialize(taskKey, source)
    }
    else None
  }

  private[tasks] def migrateApp(appName: String): mutable.Set[MarathonTask] = {
    var tasks: mutable.Set[MarathonTask] = new mutable.HashSet[MarathonTask]
    val variable = fetchFromState(getLegacyKey(appName))
    val bytes = variable.value
    if (bytes.length > 0) {
      val source = new ObjectInputStream(new ByteArrayInputStream(bytes))
      val fetchedTasks = legacyDeserialize(appName, source)
      if (fetchedTasks.size > 0)
        tasks = fetchedTasks
    }
    state.expunge(variable)
    tasks.foreach(task => store(appName, task))
    tasks
  }

  private[tasks] def deserialize(taskKey: String, source: ObjectInputStream): Option[MarathonTask] = {
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

  private[tasks] def legacyDeserialize(appName: String,
                                       source: ObjectInputStream): mutable.HashSet[MarathonTask] = {
    var results = mutable.HashSet[MarathonTask]()

    if (source.available > 0) {
      try {
        val size = source.readInt
        val bytes = new Array[Byte](size)
        source.readFully(bytes)
        val app = MarathonApp.parseFrom(bytes)
        if (app.getName != appName) {
          log.warn(s"App name from task state for $appName is wrong!  Got '${app.getName}' Continuing anyway...")
        }
        results ++= app.getTasksList.asScala.toSet
      }
      catch {
        case e: com.google.protobuf.InvalidProtocolBufferException =>
          log.warn(s"Unable to deserialize task state for $appName", e)
      }
    }
    else {
      log.warn(s"Unable to deserialize task state for $appName")
    }
    results
  }

  private[tasks] def serialize(task: MarathonTask, sink: ObjectOutputStream): Unit = {
    val size = task.getSerializedSize
    sink.writeInt(size)
    sink.write(task.toByteArray)
    sink.flush
  }

  private[tasks] def store(appName: String, task: MarathonTask): Future[Variable] = {
    val oldVar = fetchFromState(getKey(appName, task.getId))
    val bytes = new ByteArrayOutputStream()
    val output = new ObjectOutputStream(bytes)
    serialize(task, output)
    val newVar = oldVar.mutate(bytes.toByteArray)
    state.store(newVar)
  }

}

object TaskTracker {

  class App(
    val appName: String,
    var tasks: mutable.Set[MarathonTask],
    var shutdown: Boolean)

}
