package mesosphere.marathon.tasks

import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.util.RateLimiter

import scala.collection.mutable.SynchronizedPriorityQueue
import scala.concurrent.duration.Deadline
import scala.util.Try

/**
  * Utility class to stage tasks before they get scheduled
  */
class TaskQueue {

  import mesosphere.marathon.tasks.TaskQueue._

  protected[marathon] val rateLimiter = new RateLimiter

  protected[tasks] var queue =
    new SynchronizedPriorityQueue[QueuedTask]()(AppConstraintsOrdering)

  def list(): Seq[QueuedTask] = queue.to[scala.collection.immutable.Seq]

  def listApps(): Seq[AppDefinition] = list.map(_.app)

  def poll(): Option[QueuedTask] = Try(queue.dequeue()).toOption

  def add(app: AppDefinition): Unit =
    queue += QueuedTask(app, rateLimiter.getDelay(app))

  /**
    * Number of tasks in the queue for the given app
    *
    * @param app The app
    * @return count
    */
  def count(app: AppDefinition): Int = queue.count(_.app.id == app.id)

  def purge(appId: PathId): Unit = {
    val retained = queue.filterNot(_.app.id == appId)
    removeAll()
    queue ++= retained
  }

  def addAll(xs: Seq[QueuedTask]): Unit = queue ++= xs

  def removeAll(): Seq[QueuedTask] = queue.dequeueAll

}

object TaskQueue {

  protected[marathon] case class QueuedTask(app: AppDefinition, delay: Deadline)

  protected object AppConstraintsOrdering extends Ordering[QueuedTask] {
    def compare(t1: QueuedTask, t2: QueuedTask): Int =
      t1.app.constraints.size compare t2.app.constraints.size
  }
}
