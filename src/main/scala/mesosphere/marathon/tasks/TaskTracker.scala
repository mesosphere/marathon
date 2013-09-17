package mesosphere.marathon.tasks

import scala.collection._
import org.apache.mesos.Protos.TaskID
import javax.inject.Inject
import org.apache.mesos.state.State
import java.util.logging.Logger
import mesosphere.marathon.Protos.MarathonTask
import java.io._
import scala.Some

/**
 * @author Tobi Knaup
 */

class TaskTracker @Inject()(state: State) {

  private[this] val log = Logger.getLogger(getClass.getName)

  val prefix = "tasks:"

  private val tasks = new mutable.HashMap[String, mutable.Set[MarathonTask]] with
    mutable.SynchronizedMap[String, mutable.Set[MarathonTask]]
  private val stagedTasks = new mutable.HashMap[String, MarathonTask] with
    mutable.SynchronizedMap[String, MarathonTask]

  def get(appName: String) = {
    tasks.getOrElseUpdate(appName, fetch(appName))
  }

  def count(appName: String) = {
    get(appName).size
  }

  def drop(appName: String, n: Int) = {
    get(appName).drop(n)
  }

  def starting(appName: String, task: MarathonTask) {
    // Keep this here so running() can pick it up
    stagedTasks(task.getId) = task
  }

  def running(appName: String, taskId: TaskID) {
    stagedTasks.remove(taskId.getValue) match {
      case Some(task) => {
        get(appName) += task
      }
      case None => {
        log.warning(s"No staged task for ID ${taskId.getValue}")
        val exists = get(appName).exists(_.getId == taskId.getValue)
        if (!exists) {
          // We lost track of the host and port of this task, but still need to keep track of it
          val task = MarathonTasks.makeTask(taskId.getValue, "", 0, List())
          get(appName) += task
        }
      }
    }
    store(appName)
  }

  def terminated(appName: String, taskId: TaskID) {
    tasks(appName) = get(appName).filterNot(_.getId == taskId.getValue)
    store(appName)
  }

  def startUp(appName: String) {
    // NO-OP
  }

  def shutDown(appName: String) {
    val variable = fetchVariable(appName)
    state.expunge(variable)
  }

  def newTaskId(appName: String) = {
    val running = count(appName)
    val staged = stagedTasks.keys.count(TaskIDUtil.appID(_) == appName)
    TaskID.newBuilder()
      .setValue(TaskIDUtil.taskId(appName, running + staged))
      .build
  }

  def fetch(appName: String): mutable.Set[MarathonTask] = {
    val bytes = fetchVariable(appName).value()
    if (bytes.length > 0) {
      val source = new ObjectInputStream(new ByteArrayInputStream(bytes))
      deserialize(source)
      //set.map(map => MarathonTask(map("id"), map("host"), map("port").asInstanceOf[Int], map("attributes"))
    } else {
      new mutable.HashSet[MarathonTask]()
    }
  }

  def deserialize(source: ObjectInputStream): mutable.HashSet[MarathonTask] = {
    var results = mutable.HashSet[MarathonTask]()
    while (source.available() > 0) {
      val size = source.readInt
      val bytes = new Array[Byte](size)
      source.readFully(bytes)
      results += MarathonTask.parseFrom(bytes)
    }
    results
  }

  def serialize(tasks: Set[MarathonTask], sink: ObjectOutputStream) = {
    for (task <- tasks) {
      val size = task.getSerializedSize
      sink.writeInt(size)
      sink.write(task.toByteArray)
    }
    sink.flush()
  }

  def fetchVariable(appName: String) = {
    state.fetch(prefix + appName).get()
  }

  def store(appName: String) {
    val oldVar = fetchVariable(appName)
    val bytes = new ByteArrayOutputStream()
    val output = new ObjectOutputStream(bytes)
    serialize(tasks(appName), output)
    val newVar = oldVar.mutate(bytes.toByteArray)
    state.store(newVar)
  }
}