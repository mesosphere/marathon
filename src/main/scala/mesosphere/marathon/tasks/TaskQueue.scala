package mesosphere.marathon.tasks

import java.util.concurrent.PriorityBlockingQueue

import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.util.RateLimiter

import scala.concurrent.duration.Deadline
import scala.collection.mutable
import scala.collection.immutable.Seq
import scala.collection.JavaConverters._

/**
  * Utility class to stage tasks before they get scheduled
  */
class TaskQueue {

  import mesosphere.marathon.tasks.TaskQueue._

  protected[marathon] val rateLimiter = new RateLimiter

  // we used SynchronizedPriorityQueue before, but it has been deprecated
  // because it is not safe to use
  protected[tasks] var queue =
    new PriorityBlockingQueue[QueuedTask](11, AppConstraintsOrdering.reverse)

  def list: Seq[QueuedTask] = queue.asScala.to[scala.collection.immutable.Seq]

  def listApps: Seq[AppDefinition] = list.map(_.app)

  def poll(): Option[QueuedTask] = Option(queue.poll())

  def add(app: AppDefinition): Unit =
    queue.add(QueuedTask(app, rateLimiter.getDelay(app)))

  /**
    * Number of tasks in the queue for the given app
    *
    * @param app The app
    * @return count
    */
  def count(app: AppDefinition): Int = queue.asScala.count(_.app.id == app.id)

  def purge(appId: PathId): Unit = {
    val retained = queue.asScala.filterNot(_.app.id == appId)
    removeAll()
    queue.addAll(retained.asJavaCollection)
  }

  /**
    * Retains only elements that satisfy the supplied predicate.
    */
  def retain(f: (QueuedTask => Boolean)): Unit =
    queue.iterator.asScala.foreach { qt => if (!f(qt)) queue.remove(qt) }

  def addAll(xs: Seq[QueuedTask]): Unit = queue.addAll(xs.asJavaCollection)

  def removeAll(): Seq[QueuedTask] = {
    val builder = new java.util.ArrayList[QueuedTask]()
    queue.drainTo(builder)
    builder.asScala.to[Seq]
  }

}

object TaskQueue {

  protected[marathon] case class QueuedTask(app: AppDefinition, delay: Deadline)

  protected object AppConstraintsOrdering extends Ordering[QueuedTask] {
    def compare(t1: QueuedTask, t2: QueuedTask): Int =
      t1.app.constraints.size compare t2.app.constraints.size
  }
}
