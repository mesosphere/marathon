package mesosphere.marathon

import scala.collection.concurrent.TrieMap
import scala.collection.Set
import org.apache.mesos.Protos.TaskID
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author Tobi Knaup
 */

class TaskTracker {

  val tasks = new TrieMap[String, Set[TaskID]]
  val counters = new TrieMap[String, AtomicInteger]

  def get(appName: String) = {
    tasks.getOrElseUpdate(appName, Set())
  }

  def count(appName: String) = {
    get(appName).size
  }

  def drop(appName: String, n: Int) = {
    get(appName).drop(n)
  }

  def add(appName: String, taskId: TaskID) {
    tasks.synchronized {
      tasks(appName) = get(appName) + taskId
    }
  }

  def remove(appName: String, taskId: TaskID) {
    tasks.synchronized {
      tasks(appName) = get(appName) - taskId
    }
  }

  def newTaskId(appName: String) = {
    val count = counters.getOrElseUpdate(appName, new AtomicInteger()).getAndIncrement
    TaskID.newBuilder()
      .setValue(TaskIDUtil.taskId(appName, count))
      .build
  }
}