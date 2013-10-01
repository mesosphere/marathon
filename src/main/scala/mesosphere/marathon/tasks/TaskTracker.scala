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

  class StagedTask(
    val marathonTask: MarathonTask,
    val appName: String,
    val startedAt: Long
  )

  private val tasks = new mutable.HashMap[String, mutable.Set[MarathonTask]] with
    mutable.SynchronizedMap[String, mutable.Set[MarathonTask]]
  private val stagedTasks = new mutable.HashMap[String, StagedTask] with
    mutable.SynchronizedMap[String, StagedTask]
  private val stagedCount = new mutable.HashMap[String, Int] with
    mutable.SynchronizedMap[String, Int].withDefaultValue(0)

  def get(appName: String) = {
    tasks.getOrElseUpdate(appName, fetch(appName))
  }

  def list = {
    apps
  }

  def count(appName: String) = {
    get(appName).size + stagedCount(appName)
  }

  def drop(appName: String, n: Int) = {
    get(appName).drop(n)
  }

  def starting(appName: String, task: MarathonTask) {
    // Keep this here so running() can pick it up
    stagedTasks(task.getId) = new StagedTask(task, appName, System.currentTimeMillis)
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
          val task = MarathonTasks.makeTask(taskId, "", List(), List())
          get(appName) += task
          task
        }
      }
    }
    store(appName).map(_ => Some(task))
  }

  def terminated(appName: String,
                 taskId: String): Future[Option[MarathonTask]] = {
    val appTasks = get(appName)
    // Also remove from staged tasks, if it exists.
    if (stagedTasks.contains(taskId)) {
      stagedTasks.remove(taskId)
      stagedCount(appName) -= 1
    }
    appTasks.find(_.getId == taskId) match {
      case Some(task) => {
        tasks(appName) = appTasks - task
        store(appName).map(_ => Some(task))
      }
      case None => None
    }
  }

  def startUp(appName: String) {
    // NO-OP
  }

  def shutDown(appName: String) {
    val variable = fetchVariable(appName)
    state.expunge(variable)
  }

  def newTaskId(appName: String) = {
    val taskCount = count(appName)
    TaskID.newBuilder()
      .setValue(TaskIDUtil.taskId(appName, taskCount))
      .build
  }

  def fetch(appName: String): mutable.Set[MarathonTask] = {
    val bytes = fetchVariable(appName).value()
    if (bytes.length > 0) {
      val source = new ObjectInputStream(new ByteArrayInputStream(bytes))
      deserialize(source)
    }

    if (tasks.contains(appName)) {
      tasks(appName)
      //set.map(map => MarathonTask(map("id"), map("host"), map("port").asInstanceOf[Int], map("attributes"))
    } else {
      new mutable.HashSet[MarathonTask]()
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
        if (!tasks.contains(task.getId)) {
          tasks(task.getId) =
            new mutable.HashSet[MarathonTask]()
        }
        tasks(task.getId) += task.getTask
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
    for (key <- tasks.keySet) {
      for (task <- tasks(key)) {
        taskStateBuilder.addRunning(
          RunningTask.newBuilder()
          .setId(key)
          .setTask(task)
        )
      }
    }
    for (key <- stagedTasks.keySet) {
      val task = stagedTasks(key)
      taskStateBuilder.addStaged(
        StagedTask.newBuilder()
        .setId(key)
        .setTask(task.marathonTask)
        .setStarted(task.startedAt)
        .setAppName(task.appName)
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
    val expired = now - Main.getConfiguration.taskLaunchTimeout()
    var toKill = new mutable.HashSet[String]
    for (key <- stagedTasks.keySet) {
      val task = stagedTasks(key)
      if (task.startedAt < expired) {
        log.warning(s"Task '${key}' was started ${(now - task.startedAt)/1000}s ago and has not yet started")
        toKill += key
      }
    }
    for (task <- toKill) {
      // Remove each from staged tasks, if it exists.
      if (stagedTasks.contains(task)) {
        stagedCount(stagedTasks(task).appName) -= 1
        stagedTasks.remove(task)
      }
    }
    toKill.toList
  }
}
