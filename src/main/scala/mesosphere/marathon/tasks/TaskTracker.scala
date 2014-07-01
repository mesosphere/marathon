package mesosphere.marathon.tasks

import mesosphere.marathon.state.PathId
import scala.collection._
import scala.collection.JavaConverters._
import org.apache.mesos.Protos.{ TaskID, TaskStatus }
import javax.inject.Inject
import org.apache.mesos.state.{ Variable, State }
import mesosphere.marathon.Protos._
import mesosphere.marathon.{ MarathonConf, Main }
import java.io._
import scala.concurrent.Future
import org.apache.log4j.Logger
import mesosphere.util.{ ThreadPoolContext, BackToTheFuture }

class TaskTracker @Inject() (state: State, config: MarathonConf) {

  import TaskTracker.App
  import ThreadPoolContext.context
  import BackToTheFuture.futureToFuture

  implicit val timeout = config.zkFutureTimeout

  private[this] val log = Logger.getLogger(getClass.getName)

  val prefix = "tasks:"

  private[this] val apps = new mutable.HashMap[PathId, App] with mutable.SynchronizedMap[PathId, App]

  def get(appId: PathId): mutable.Set[MarathonTask] = apps.getOrElseUpdate(appId, fetchApp(appId)).tasks

  def list: mutable.HashMap[PathId, App] = apps

  def count(appId: PathId): Int = get(appId).size

  def contains(appId: PathId): Boolean = apps.contains(appId)

  def take(appId: PathId, n: Int): Set[MarathonTask] = get(appId).take(n)

  def starting(appId: PathId, task: MarathonTask): Unit = {
    // Keep this here so running() can pick it up
    get(appId) += task
  }

  def running(appId: PathId, status: TaskStatus): Future[MarathonTask] = {
    val taskId = status.getTaskId.getValue
    val task = get(appId).find(_.getId == taskId) match {
      case Some(stagedTask) =>
        get(appId).remove(stagedTask)
        stagedTask.toBuilder
          .setStartedAt(System.currentTimeMillis)
          .addStatuses(status)
          .build

      case _ =>
        log.warn(s"No staged task for ID $taskId")
        // We lost track of the host and port of this task, but still need to keep track of it
        MarathonTask.newBuilder
          .setId(taskId)
          .setStagedAt(System.currentTimeMillis)
          .setStartedAt(System.currentTimeMillis)
          .addStatuses(status)
          .build
    }
    get(appId) += task
    store(appId).map(_ => task)
  }

  def terminated(appId: PathId, status: TaskStatus): Future[Option[MarathonTask]] = {
    val appTasks = get(appId)
    val taskId = status.getTaskId.getValue

    appTasks.find(_.getId == taskId) match {
      case Some(task) =>
        val result = apps.get(appId).map { app =>
          app.tasks = appTasks - task
          val ret = store(appId).map(_ => Some(task))
          log.info(s"Task $taskId removed from TaskTracker")
          if (app.shutdown && app.tasks.isEmpty) {
            // Are we shutting down this app? If so, expunge
            expunge(appId)
          }
          ret
        }

        result getOrElse Future.successful(None)
      case None =>
        if (apps(appId).shutdown && apps(appId).tasks.isEmpty) {
          // Are we shutting down this app? If so, expunge
          expunge(appId)
        }
        Future.successful(None)
    }
  }

  def statusUpdate(appId: PathId, status: TaskStatus): Future[Option[MarathonTask]] = {
    val taskId = status.getTaskId.getValue
    get(appId).find(_.getId == taskId) match {
      case Some(task) =>
        get(appId).remove(task)
        val updatedTask = task.toBuilder
          .addStatuses(status)
          .build
        get(appId) += updatedTask
        store(appId).map(_ => Some(updatedTask))

      case _ =>
        log.warn(s"No task for ID ${taskId}")
        Future.successful(None)
    }
  }

  def expunge(appId: PathId): Unit = {
    val variable = fetchFromState(appId)
    state.expunge(variable)
    apps.remove(appId)
    log.info(s"Expunged app ${appId}")
  }

  def shutDown(appId: PathId): Unit =
    apps.getOrElseUpdate(appId, fetchApp(appId)).shutdown = true

  def newTaskId(appId: PathId): TaskID = {
    TaskID.newBuilder()
      .setValue(TaskIDUtil.taskId(appId))
      .build
  }

  def fetchApp(appId: PathId): App = {
    val bytes = fetchFromState(appId).value
    if (bytes.length > 0) {
      val source = new ObjectInputStream(new ByteArrayInputStream(bytes))
      val fetchedTasks = deserialize(appId, source)
      if (fetchedTasks.size > 0) {
        apps(appId) = new App(appId, fetchedTasks, false)
      }
    }

    if (apps.contains(appId)) {
      apps(appId)
    }
    else {
      new App(appId, new mutable.HashSet[MarathonTask](), false)
    }
  }

  def deserialize(appId: PathId, source: ObjectInputStream): mutable.HashSet[MarathonTask] = {
    var results = mutable.HashSet[MarathonTask]()
    try {
      if (source.available > 0) {
        val size = source.readInt
        val bytes = new Array[Byte](size)
        source.readFully(bytes)
        val app = MarathonApp.parseFrom(bytes)
        if (app.getName != appId.toString) {
          log.warn(s"App name from task state for $appId is wrong!  Got '${app.getName}' Continuing anyway...")
        }
        results ++= app.getTasksList.asScala.toSet
      }
      else {
        log.warn(s"Unable to deserialize task state for $appId")
      }
    }
    catch {
      case e: com.google.protobuf.InvalidProtocolBufferException =>
        log.warn(s"Unable to deserialize task state for $appId", e)
    }
    results
  }

  def getProto(appId: PathId, tasks: Set[MarathonTask]): MarathonApp = {
    MarathonApp.newBuilder
      .setName(appId.toString)
      .addAllTasks(tasks.toList.asJava)
      .build
  }

  def serialize(appId: PathId,
                tasks: Set[MarathonTask],
                sink: ObjectOutputStream): Unit = {
    val app = getProto(appId, tasks)

    val size = app.getSerializedSize
    sink.writeInt(size)
    sink.write(app.toByteArray)
    sink.flush()
  }

  def fetchFromState(appId: PathId) = state.fetch(prefix + appId.safePath).get()

  def store(appId: PathId): Future[Variable] = {
    val oldVar = fetchFromState(appId)
    val bytes = new ByteArrayOutputStream()
    val output = new ObjectOutputStream(bytes)
    serialize(appId, get(appId), output)
    val newVar = oldVar.mutate(bytes.toByteArray)
    state.store(newVar)
  }

  def checkStagedTasks: Iterable[MarathonTask] = {
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
}

object TaskTracker {
  class App(
    val appName: PathId,
    var tasks: mutable.Set[MarathonTask],
    var shutdown: Boolean)
}
