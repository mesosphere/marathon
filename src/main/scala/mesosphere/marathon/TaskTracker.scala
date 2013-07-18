package mesosphere.marathon

import scala.collection._
import org.apache.mesos.Protos.TaskID
import javax.inject.{Named, Inject}
import org.apache.mesos.state.State
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.twitter.common.zookeeper.ZooKeeperClient
import java.util.logging.Logger

/**
 * @author Tobi Knaup
 */

class TaskTracker @Inject()(
    state: State,
    zkClient: ZooKeeperClient,
    @Named(ModuleNames.NAMED_SERVER_SET_PATH) zkPath: String,
    log: Logger) {

  val prefix = "tasks:"

  private val mapper = new ObjectMapper()
  mapper.registerModule(DefaultScalaModule)

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
    stagedTasks(task.id) = task
  }

  def running(appName: String, taskId: TaskID) {
    stagedTasks.remove(taskId.getValue) match {
      case Some(task) => {
        get(appName) += task
      }
      case None => {
        log.warning(s"No staged task for ID ${taskId.getValue}")
        val exists = get(appName).exists(_.id == taskId.getValue)
        if (!exists) {
          // We lost track of the host and port of this task, but still need to keep track of it
          val task = MarathonTask(taskId.getValue, "", 0)
          get(appName) += task
        }
      }
    }
    store(appName)
  }

  def terminated(appName: String, taskId: TaskID) {
    tasks(appName) = get(appName).filterNot(_.id == taskId.getValue)
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
    val taskCount = count(appName)
    TaskID.newBuilder()
      .setValue(TaskIDUtil.taskId(appName, taskCount))
      .build
  }

  private def fetch(appName: String): mutable.Set[MarathonTask] = {
    val bytes = fetchVariable(appName).value()
    if (bytes.length > 0) {
      // TODO figure out how to properly read this
      val set = mapper.readValue(bytes, classOf[mutable.Set[Map[String, String]]])
      set.map(map => MarathonTask(map("id"), map("host"), map("port").asInstanceOf[Int]))
    } else {
      new mutable.HashSet[MarathonTask]()
    }
  }

  private def fetchVariable(appName: String) = {
    state.fetch(prefix + appName).get()
  }

  private def store(appName: String) {
    val oldVar = fetchVariable(appName)
    val bytes = mapper.writeValueAsBytes(tasks(appName))
    val newVar = oldVar.mutate(bytes)
    state.store(newVar)
  }
}