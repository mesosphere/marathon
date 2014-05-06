package mesosphere.marathon.tasks

import scala.collection._
import scala.collection.JavaConverters._
import org.apache.mesos.Protos.{TaskID, TaskStatus}
import javax.inject.Inject
import org.apache.mesos.state.{Variable, State}
import mesosphere.marathon.Protos._
import mesosphere.marathon.Main
import java.io._
import scala.Some
import scala.concurrent.{ExecutionContext, Future}
import org.apache.log4j.Logger

/**
 * @author Tobi Knaup
 */

class TaskTracker @Inject()(state: State) {

  import TaskTracker.App
  import ExecutionContext.Implicits.global
  import mesosphere.util.BackToTheFuture.futureToFuture

  private[this] val log = Logger.getLogger(getClass.getName)

  val prefix = "tasks:"

  private[this] val apps = new mutable.HashMap[String, App] with
    mutable.SynchronizedMap[String, App]

  def get(appName: String): mutable.Set[MarathonTask] =
    apps.getOrElseUpdate(appName, fetchApp(appName)).tasks

  def list: mutable.HashMap[String, App] = apps

  def count(appName: String): Int = get(appName).size

  def contains(appName: String): Boolean = apps.contains(appName)

  def take(appName: String, n: Int): Set[MarathonTask] = get(appName).take(n)

  def starting(appName: String, task: MarathonTask): Unit = {
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
    store(appName).map(_ => task)
  }

  def terminated(appName: String,
                 status: TaskStatus): Future[Option[MarathonTask]] = {
    val appTasks = get(appName)
    val taskId = status.getTaskId.getValue

    appTasks.find(_.getId == taskId) match {
      case Some(task) =>
        apps(appName).tasks = appTasks - task

        val ret = store(appName).map(_ => Some(task))

        log.info(s"Task ${taskId} removed from TaskTracker")

        if (apps(appName).shutdown && apps(appName).tasks.isEmpty) {
          // Are we shutting down this app? If so, expunge
          expunge(appName)
        }

        ret

      case None =>
        if (apps(appName).shutdown && apps(appName).tasks.isEmpty) {
          // Are we shutting down this app? If so, expunge
          expunge(appName)
        }
        Future.successful(None)
    }
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
        store(appName).map(_ => Some(updatedTask))

      case _ =>
        log.warn(s"No task for ID ${taskId}")
        Future.successful(None)
    }
  }

  def expunge(appName: String): Unit = {
    val variable = fetchFromState(appName)
    state.expunge(variable)
    apps.remove(appName)
    log.warn(s"Expunged app ${appName}")
  }

  def shutDown(appName: String): Unit =
    apps.getOrElseUpdate(appName, fetchApp(appName)).shutdown = true

  def newTaskId(appName: String): TaskID = {
    val taskCount = count(appName)
    TaskID.newBuilder()
      .setValue(TaskIDUtil.taskId(appName, taskCount))
      .build
  }

  def fetchApp(appName: String): App = {
    val bytes = fetchFromState(appName).value
    if (bytes.length > 0) {
      val source = new ObjectInputStream(new ByteArrayInputStream(bytes))
      val fetchedTasks = deserialize(appName, source)
      if (fetchedTasks.size > 0) {
        apps(appName) = new App(appName, fetchedTasks, false)
      }
    }

    if (apps.contains(appName)) {
      apps(appName)
    } else {
      new App(appName, new mutable.HashSet[MarathonTask](), false)
    }
  }

  def deserialize(appName: String,
                  source: ObjectInputStream): mutable.HashSet[MarathonTask] = {
    var results = mutable.HashSet[MarathonTask]()
    try {
      if (source.available > 0) {
        val size = source.readInt
        val bytes = new Array[Byte](size)
        source.readFully(bytes)
        val app = MarathonApp.parseFrom(bytes)
        if (app.getName != appName) {
          log.warn(s"App name from task state for $appName is wrong!  Got '${app.getName}' Continuing anyway...")
        }
        results ++= app.getTasksList.asScala.toSet
      } else {
        log.warn(s"Unable to deserialize task state for $appName")
      }
    } catch {
      case e: com.google.protobuf.InvalidProtocolBufferException =>
        log.warn(s"Unable to deserialize task state for $appName", e)
    }
    results
  }

  def getProto(appName: String, tasks: Set[MarathonTask]): MarathonApp = {
    MarathonApp.newBuilder
      .setName(appName)
      .addAllTasks(tasks.toList.asJava)
      .build
  }

  def serialize(appName: String,
                tasks: Set[MarathonTask],
                sink: ObjectOutputStream): Unit = {
    val app = getProto(appName, tasks)

    val size = app.getSerializedSize
    sink.writeInt(size)
    sink.write(app.toByteArray)
    sink.flush
  }

  def fetchFromState(appName: String) = state.fetch(prefix + appName).get()

  def store(appName: String): Future[Variable] = {
    val oldVar = fetchFromState(appName)
    val bytes = new ByteArrayOutputStream()
    val output = new ObjectOutputStream(bytes)
    serialize(appName, get(appName), output)
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
      log.warn(s"Task '${t.getId}' was staged ${(now - t.getStagedAt)/1000}s ago and has not yet started")
    })
    toKill
  }
}

object TaskTracker {
  class App(
    val appName: String,
    var tasks: mutable.Set[MarathonTask],
    var shutdown: Boolean
  )
}
