package mesosphere.marathon.tasks

import java.util.concurrent.atomic.AtomicInteger

import mesosphere.marathon.state.{ Timestamp, AppDefinition, PathId }
import mesosphere.util.RateLimiter
import org.apache.log4j.Logger

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Deadline
import scala.collection.immutable.Seq

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

  def poll(): Option[QueuedTask] =
    apps.values.toSeq.sortWith {
      case (a, b) =>
        a.app.constraints.size > b.app.constraints.size
    }.find {
      case QueuedTask(_, count, _) => count.decrementAndGet() >= 0
    }

  def add(app: AppDefinition): Unit = add(app, 1)

  def add(app: AppDefinition, count: Int): Unit = {
    val queuedTask = apps.getOrElseUpdate(
      (app.id, app.version),
      QueuedTask(app, new AtomicInteger(0), rateLimiter.getDelay(app)))
    queuedTask.count.addAndGet(count)
  }

  /**
    * Number of tasks in the queue for the given app
    *
    * @param app The app
    * @return count
    */
  def count(app: AppDefinition): Int = apps.get((app.id, app.version)).map(_.count.get()).getOrElse(0)

  def purge(appId: PathId): Unit = {
    for {
      QueuedTask(app, _, _) <- apps.values
      if app.id == appId
    } apps.remove(app.id -> app.version)
  }

  /**
    * Retains only elements that satisfy the supplied predicate.
    */
  def retain(f: (QueuedTask => Boolean)): Unit =
    apps.values.foreach {
      case qt @ QueuedTask(app, _, _) => if (!f(qt)) apps.remove(app.id -> app.version)
    }

  def pollMatching[B](f: AppDefinition => Option[B]): Option[B] = {
    val sorted = apps.values.toList.sortWith { (a, b) =>
      a.app.constraints.size > b.app.constraints.size
    }

    @tailrec
    def findMatching(xs: List[QueuedTask]): Option[B] = xs match {
      case Nil => None
      case head :: tail => head match {
        case QueuedTask(app, _, delay) if delay.hasTimeLeft() =>
          log.info(s"Delaying ${app.id} due to backoff. Time left: ${delay.timeLeft}.")
          findMatching(tail)

        case QueuedTask(app, count, delay) =>
          val res = f(app)
          if (res.isDefined && count.decrementAndGet() >= 0) {
            res
          }
          else {
            // app count is 0, so we can remove this app from the queue
            apps.remove(app.id -> app.version)
            findMatching(tail)
          }
      }
    }

    findMatching(sorted)
  }
}

object TaskQueue {

  protected[marathon] case class QueuedTask(app: AppDefinition, count: AtomicInteger, delay: Deadline)

  protected object AppConstraintsOrdering extends Ordering[QueuedTask] {
    def compare(t1: QueuedTask, t2: QueuedTask): Int =
      t1.app.constraints.size compare t2.app.constraints.size
  }
}
