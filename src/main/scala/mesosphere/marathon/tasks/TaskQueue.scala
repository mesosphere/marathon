package mesosphere.marathon.tasks

import java.util.concurrent.atomic.AtomicInteger

import mesosphere.marathon.state.{ Timestamp, AppDefinition, PathId }
import mesosphere.util.RateLimiter
import org.apache.log4j.Logger

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Deadline
import scala.collection.immutable.Seq

object TaskQueue {
  protected[marathon] case class QueuedTask(app: AppDefinition, count: AtomicInteger)

  protected[tasks] implicit object AppConstraintsOrdering extends Ordering[QueuedTask] {
    def compare(t1: QueuedTask, t2: QueuedTask): Int =
      t2.app.constraints.size compare t1.app.constraints.size
  }

  protected[tasks] implicit class AtomicIntDecrementIfPositive(val value: AtomicInteger) extends AnyVal {
    @tailrec
    final def decrementIfPositive(): Boolean = {
      val num = value.get()
      if (num <= 0) {
        false
      }
      else if (value.compareAndSet(num, num - 1)) {
        true
      }
      else {
        decrementIfPositive()
      }
    }
  }
}

/**
  * Utility class to stage tasks before they get scheduled
  */
class TaskQueue {

  import mesosphere.marathon.tasks.TaskQueue._

  private val log = Logger.getLogger(getClass)
  protected[marathon] val rateLimiter = new RateLimiter

  protected[tasks] var apps = TrieMap.empty[(PathId, Timestamp), QueuedTask]

  def list: Seq[QueuedTask] = apps.values.to[Seq]

  def listApps: Seq[AppDefinition] = list.map(_.app)

  def poll(): Option[QueuedTask] = {
    // TODO: make prioritization pluggable
    // Marathon prioritizes tasks by number of constraints, so we have to sort here
    apps.values.toSeq.sorted.find {
      case QueuedTask(_, count) => count.decrementIfPositive()
    }
  }

  def add(app: AppDefinition): Unit = add(app, 1)

  def add(app: AppDefinition, count: Int): Unit = {
    val queuedTask = apps.getOrElseUpdate(
      (app.id, app.version),
      QueuedTask(app, new AtomicInteger(0)))
    queuedTask.count.addAndGet(count)
  }

  // TODO: should only return the count for the same version
  /**
    * Number of tasks in the queue for the given app
    *
    * @param app The app
    * @return count
    */
  def count(app: AppDefinition): Int = apps.values.foldLeft(0) {
    case (count, task) if task.app.id == app.id => count + task.count.get()
    case (count, _)                             => count
  }

  def purge(appId: PathId): Unit = {
    for {
      QueuedTask(app, _) <- apps.values
      if app.id == appId
    } apps.remove(app.id -> app.version)
  }

  /**
    * Retains only elements that satisfy the supplied predicate.
    */
  def retain(f: (QueuedTask => Boolean)): Unit =
    apps.values.foreach {
      case qt @ QueuedTask(app, _) => if (!f(qt)) apps.remove(app.id -> app.version)
    }

  def pollMatching[B](f: AppDefinition => Option[B]): Option[B] = {
    val sorted = apps.values.toList.sorted

    @tailrec
    def findMatching(xs: List[QueuedTask]): Option[B] = xs match {
      case Nil => None
      case head :: tail => head match {
        case QueuedTask(app, _) if rateLimiter.getDelay(app).hasTimeLeft() =>
          log.info(s"Delaying ${app.id} due to backoff. Time left: ${rateLimiter.getDelay(app).timeLeft}.")
          findMatching(tail)

        case QueuedTask(app, count) =>
          val res = f(app)
          if (res.isDefined && count.decrementIfPositive()) {
            res
          }
          else {
            findMatching(tail)
          }
      }
    }

    findMatching(sorted)
  }
}
