package mesosphere.marathon.tasks

import scala.collection._
import scala.collection.JavaConverters._
import org.apache.mesos.Protos.{TaskID, TaskStatus}
import javax.inject.Inject
import org.apache.mesos.state.State
import java.util.logging.{Level, Logger}
import mesosphere.marathon.Protos._
import mesosphere.marathon.Main
import java.io._
import scala.Some
import scala.concurrent.{ExecutionContext, Future}

/**
 * @author Tobi Knaup
 */

class TaskTracker @Inject()(state: State) {

  import ExecutionContext.Implicits.global
  import mesosphere.util.BackToTheFuture._

  private[this] val log = Logger.getLogger(getClass.getName)

  val prefix = "tasks:"

  class App(
    val appName: String,
    var tasks: mutable.Set[MarathonTask],
    var shutdown: Boolean
  )

  private val apps = new mutable.HashMap[String, App] with
    mutable.SynchronizedMap[String, App]

  def get(appName: String) = {
    apps.getOrElseUpdate(appName, fetchApp(appName)).tasks
  }

  def list = {
    apps
  }

  def count(appName: String) = {
    get(appName).size
  }

  def contains(appName: String) = {
    apps.contains(appName)
  }

  def take(appName: String, n: Int) = {
    get(appName).take(n)
  }

  def starting(appName: String, task: MarathonTask) {
    // Keep this here so running() can pick it up
    get(appName) += task
  }

  def running(appName: String, status: TaskStatus): Future[MarathonTask] = {
    val taskId = status.getTaskId.getValue
    val task = get(appName).find(_.getId == taskId) match {
      case Some(stagedTask) => {
        get(appName).remove(stagedTask)
        stagedTask.toBuilder
          .setStartedAt(System.currentTimeMillis)
          .addStatuses(status)
          .build
      }
      case _ => {
        log.warning(s"No staged task for ID ${taskId}")
        // We lost track of the host and port of this task, but still need to keep track of it
        MarathonTask.newBuilder
          .setId(taskId)
          .setStagedAt(System.currentTimeMillis)
          .setStartedAt(System.currentTimeMillis)
          .addStatuses(status)
          .build
      }
    }
    get(appName) += task
    store(appName).map(_ => task)
  }

  def terminated(appName: String,
                 status: TaskStatus): Future[Option[MarathonTask]] = {
    val now = System.currentTimeMillis
    val appTasks = get(appName)
    val taskId = status.getTaskId.getValue

    appTasks.find(_.getId == taskId) match {
      case Some(task) => {
        apps(appName).tasks = appTasks - task

        val ret = store(appName).map(_ => Some(task))

        log.info(s"Task ${taskId} removed from TaskTracker")

        if (apps(appName).shutdown && apps(appName).tasks.isEmpty) {
          // Are we shutting down this app? If so, expunge
          expunge(appName)
        }

        ret
      }
      case None =>
        if (apps(appName).shutdown && apps(appName).tasks.isEmpty) {
          // Are we shutting down this app? If so, expunge
          expunge(appName)
        }
        None
    }
  }

  def statusUpdate(appName: String,
                   status: TaskStatus): Future[Option[MarathonTask]] = {
    val taskId = status.getTaskId.getValue
    get(appName).find(_.getId == taskId) match {
      case Some(task) => {
        get(appName).remove(task)
        val updatedTask = task.toBuilder
          .addStatuses(status)
          .build
        get(appName) += updatedTask
        store(appName).map(_ => Some(updatedTask))
      }
      case _ => {
        log.warning(s"No task for ID ${taskId}")
        None
      }
    }
  }

  def expunge(appName: String) {
    val variable = fetchFromState(appName)
    state.expunge(variable)
    apps.remove(appName)
    log.warning(s"Expunged app ${appName}")
  }

  def shutDown(appName: String) {
    apps.getOrElseUpdate(appName, fetchApp(appName)).shutdown = true
  }

  def newTaskId(appName: String) = {
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
        apps(appName) =
          new App(appName,
            fetchedTasks, false)
      }
    }

    if (apps.contains(appName)) {
      apps(appName)
      //set.map(map => MarathonTask(map("id"), map("host"), map("port").asInstanceOf[Int], map("attributes"))
    } else {
      new App(appName,
        new mutable.HashSet[MarathonTask](), false)
    }
  }

  def deserialize(appName: String, source: ObjectInputStream)
    : mutable.HashSet[MarathonTask] = {
    var results = mutable.HashSet[MarathonTask]()
    try {
      if (source.available > 0) {
        val size = source.readInt
        val bytes = new Array[Byte](size)
        source.readFully(bytes)
        val app = MarathonApp.parseFrom(bytes)
        if (app.getName != appName) {
          log.warning(s"App name from task state for ${appName} is wrong!  Got '${app.getName}' Continuing anyway...")
        }
        results ++= app.getTasksList.asScala.toSet
      } else {
        log.warning(s"Unable to deserialize task state for ${appName}")
      }
    } catch {
      case e: com.google.protobuf.InvalidProtocolBufferException =>
        log.log(Level.WARNING, "Unable to deserialize task state for ${appName}", e)
    }
    results
  }

  def getProto(appName: String, tasks: Set[MarathonTask]): MarathonApp = {
    MarathonApp.newBuilder
      .setName(appName)
      .addAllTasks(tasks.toList.asJava)
      .build
  }

  def serialize(appName: String, tasks: Set[MarathonTask], sink: ObjectOutputStream) {
    val app = getProto(appName, tasks)

    val size = app.getSerializedSize
    sink.writeInt(size)
    sink.write(app.toByteArray)
    sink.flush
  }

  def fetchFromState(appName: String) = {
    state.fetch(prefix + appName).get()
  }

  def store(appName: String) {
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
      log.warning(s"Task '${t.getId}' was staged ${(now - t.getStagedAt)/1000}s ago and has not yet started")
    })
    toKill
  }
}
