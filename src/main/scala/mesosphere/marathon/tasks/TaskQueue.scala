package mesosphere.marathon.tasks

import java.util.concurrent.PriorityBlockingQueue
import mesosphere.marathon.api.v1.AppDefinition
import scala.collection.JavaConverters._
import scala.collection.immutable.IndexedSeq
import java.util
import java.util.Comparator

/**
 * Utility class to stage tasks before they get scheduled
 *
 * @author Tobi Knaup
 */

class TaskQueue {
  import TaskQueue._

  val queue = new PriorityBlockingQueue[AppDefinition](1, AppDefinitionPriority)

  def poll(): AppDefinition = queue.poll()

  def add(app: AppDefinition): Boolean = queue.add(app)

  /**
   * Number of tasks in the queue for the given app
   *
   * @param app The app
   * @return count
   */
  def count(app: AppDefinition): Int = queue.asScala.count(_.id == app.id)

  def purge(app: AppDefinition): Unit = {
    val toRemove = queue.asScala.filter(_.id == app.id)
    toRemove foreach queue.remove
  }

  def addAll(xs: Seq[AppDefinition]): Boolean = queue.addAll(xs.asJava)

  def removeAll(): IndexedSeq[AppDefinition] = {
    val size = queue.size()
    val res = new util.ArrayList[AppDefinition](size)

    queue.drainTo(res, size)

    res.asScala.to[IndexedSeq]
  }
}

object TaskQueue {
  private object AppDefinitionPriority extends Comparator[AppDefinition] {
    override def compare(a1: AppDefinition, a2: AppDefinition): Int = {
      a2.constraints.size - a1.constraints.size
    }
  }
}
