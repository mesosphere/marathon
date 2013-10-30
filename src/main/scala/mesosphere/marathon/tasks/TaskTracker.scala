package mesosphere.marathon.tasks

import scala.collection._
import scala.collection.JavaConverters._
import org.apache.mesos.Protos.TaskID
import javax.inject.Inject
import org.apache.mesos.state.State
import java.util.logging.Logger
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

  class StagedTask(
    val marathonTask: MarathonTask,
    val appName: String
  )

  class CompletedTask(
    val marathonTask: MarathonTask,
    val appName: String,
    val finished: Long
  )

  private val apps = new mutable.HashMap[String, App] with
    mutable.SynchronizedMap[String, App]
  private val stagedTasks = new mutable.HashMap[String, StagedTask] with
    mutable.SynchronizedMap[String, StagedTask]
  private val stagedCount = new mutable.HashMap[String, Int] with
    mutable.SynchronizedMap[String, Int].withDefaultValue(0)
  private val recentCompletedTasks = new mutable.SynchronizedQueue[CompletedTask]

  def get(appName: String) = {
    apps.getOrElseUpdate(appName, fetch(appName)).tasks
  }

  def list = {
    apps
  }

  def count(appName: String) = {
    get(appName).size + stagedCount(appName) +
      recentCompletedTasks.count { t => t.appName == appName }
  }

  def recentlyCompletedCount(appName: String) = {
    recentCompletedTasks.count { t => t.appName == appName }
  }

  def take(appName: String, n: Int) = {
    get(appName).take(n)
  }

  def starting(appName: String, task: MarathonTask) {
    // Keep this here so running() can pick it up
    stagedTasks(task.getId) = new StagedTask(task, appName)
    stagedCount(appName) += 1
  }

  def running(appName: String, taskId: String): Future[Option[MarathonTask]] = {
    val task = stagedTasks.remove(taskId) match {
      case Some(task) => {
        get(appName) += task.marathonTask
        stagedCount(appName) -= 1
        task.marathonTask
      }
      case None => {
        log.warning(s"No staged task for ID ${taskId}")
        get(appName).find(_.getId == taskId).getOrElse {
          // We lost track of the host and port of this task, but still need to keep track of it
          val task = MarathonTasks.makeTask(taskId, "", List(), List(), appName)
          get(appName) += task
          task
        }
      }
    }
    store(appName).map(_ => Some(task))
  }

  def checkRecentlyCompleted = {
    val expires = System.currentTimeMillis - Main.conf.taskRateLimit()
    recentCompletedTasks.dequeueAll { t => t.finished < expires }
  }

  def terminated(appName: String,
                 taskId: String): Future[Option[MarathonTask]] = {
    val now = System.currentTimeMillis
    val appTasks = get(appName)
    // Also remove from staged tasks, if it exists.
    if (stagedTasks.contains(taskId)) {
      stagedTasks.remove(taskId)
      stagedCount(appName) -= 1
    }
    appTasks.find(_.getId == taskId) match {
      case Some(task) => {
        apps(appName).tasks = appTasks - task

        // Handle completed tasks
        recentCompletedTasks += new CompletedTask(task, appName, now)

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

  def startUp(appName: String) {
    // NO-OP
  }

  def expunge(appName: String) {
    val variable = fetchVariable(appName)
    state.expunge(variable)
    apps.remove(appName)
    log.warning(s"Expunged app ${appName}")
  }

  def shutDown(appName: String) {
    apps.getOrElseUpdate(appName, fetch(appName)).shutdown = true
  }

  def newTaskId(appName: String) = {
    val taskCount = count(appName)
    TaskID.newBuilder()
      .setValue(TaskIDUtil.taskId(appName, taskCount))
      .build
  }

  def fetch(appName: String): App = {
    val bytes = fetchVariable(appName).value()
    if (bytes.length > 0) {
      val source = new ObjectInputStream(new ByteArrayInputStream(bytes))
      deserialize(source)
    }

    if (apps.contains(appName)) {
      apps(appName)
      //set.map(map => MarathonTask(map("id"), map("host"), map("port").asInstanceOf[Int], map("attributes"))
    } else {
      new App(appName,
        new mutable.HashSet[MarathonTask](), false)
    }
  }

  def deserialize(source: ObjectInputStream) = {
    if (source.available() > 0) {
      val size = source.readInt
      val bytes = new Array[Byte](size)
      source.readFully(bytes)
      var taskState = TaskState.newBuilder.build
      try {
        taskState = TaskState.parseFrom(bytes)
      } catch {
        case e: com.google.protobuf.InvalidProtocolBufferException =>
          log.warning("Unable to parse state")
      }
      for (task <- taskState.getRunningList.asScala) {
        if (!apps.contains(task.getAppName)) {
          apps(task.getAppName) =
            new App(task.getAppName,
              new mutable.HashSet[MarathonTask](), false)
        }
        apps(task.getAppName).tasks += task
      }
      /*stagedCount.clear
      for (task <- taskState.getStagedList.asScala) {
        stagedTasks(task.getId) =
          new StagedTask(task.getTask, task.getAppName, task.getStarted)
        stagedCount(task.getAppName) += 1
      }*/
    }
  }

  def serialize(sink: ObjectOutputStream) = {
    var taskStateBuilder =
      TaskState.newBuilder()
    for (appName <- apps.keySet) {
      for (task <- apps(appName).tasks) {
        taskStateBuilder.addRunning(
          MarathonTask.newBuilder()
          .mergeFrom(task)
        )
      }
    }
    for (key <- stagedTasks.keySet) {
      val task = stagedTasks(key)
      taskStateBuilder.addStaged(
        MarathonTask.newBuilder()
        .mergeFrom(task.marathonTask)
      )
    }
    val taskState = taskStateBuilder.build
    val size = taskState.getSerializedSize
    sink.writeInt(size)
    sink.write(taskState.toByteArray)
    sink.flush()
  }

  def fetchVariable(appName: String) = {
    state.fetch(prefix + appName).get()
  }

  def store(appName: String) {
    val oldVar = fetchVariable(appName)
    val bytes = new ByteArrayOutputStream()
    val output = new ObjectOutputStream(bytes)
    serialize(output)
    val newVar = oldVar.mutate(bytes.toByteArray)
    state.store(newVar)
  }

  def checkStagedTasks = {
    val now = System.currentTimeMillis
    val expires = now - Main.conf.taskLaunchTimeout()
    var toKill = new mutable.HashSet[String]
    for (taskId <- stagedTasks.keySet) {
      val task = stagedTasks(taskId)
      if (task.marathonTask.getStarted < expires) {
        log.warning(s"Task '${taskId}' was started ${(now - task.marathonTask.getStarted)/1000}s ago and has not yet started")
        toKill += taskId
      }
    }
    for (taskId <- toKill) {
      // Remove each from staged tasks, if it exists.
      if (stagedTasks.contains(taskId)) {
        stagedCount(stagedTasks(taskId).appName) -= 1
        stagedTasks.remove(taskId)
      }
    }
    toKill.toList
  }
}
