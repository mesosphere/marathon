package mesosphere.marathon
package util

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{ ExecutionContext, Future, Promise }
import mesosphere.marathon.functional._

import scala.collection.mutable

/**
  * Allows capping the maximum number of concurrent tasks in an easy manner:
  * {{{
  *   val queue = WorkQueue("zk-access", 32, 1000)
  *   queue {
  *     // some future
  *   }
  *   queue.blocking {
  *     // some blocking method
  *   }
  *   ...
  * }}}
  *
  * @param name The name of the queue
  * @param maxConcurrent The maximum number of work items allowed in parallel.
  * @param maxQueueLength The maximum number of items allowed to queue up, if the length is exceeded,
  *                       the future will fail with an IllegalStateException
  * @param parent An optional parent queue (this allows stacking). This can come in handy with [[KeyedLock]]
  *               so that you can say "lock on this key" and "limit the total number of outstanding to 32".
  *               There can be other use cases as well, where you may want to limit the number of
  *               total operations for a class to X while limiting a particular operation to Y where Y < X.
  */
case class WorkQueue(name: String, maxConcurrent: Int, maxQueueLength: Int, parent: Option[WorkQueue] = None) {
  require(maxConcurrent > 0 && maxQueueLength >= 0)
  private case class WorkItem[T](f: () => Future[T], ctx: ExecutionContext, promise: Promise[T])
  private val queue = new ConcurrentLinkedQueue[WorkItem[_]]()
  private val totalOutstanding = new AtomicInteger(0)

  private def run[T](workItem: WorkItem[T]): Future[T] = {
    parent.fold {
      workItem.ctx.execute(new Runnable {
        override def run(): Unit = {
          val future = workItem.f()
          future.onComplete(_ => executeNextIfPossible())(workItem.ctx)
          workItem.promise.completeWith(future)
        }
      })
      workItem.promise.future
    } { p =>
      p(workItem.f())(workItem.ctx)
    }
  }

  private def executeNextIfPossible(): Unit = {
    Option(queue.poll()).fold[Unit] {
      totalOutstanding.decrementAndGet()
    } { run(_) }
  }

  def blocking[T](f: => T)(implicit ctx: ExecutionContext): Future[T] = {
    apply(Future(concurrent.blocking(f)))
  }

  def apply[T](f: => Future[T])(implicit ctx: ExecutionContext): Future[T] = {
    val previouslyOutstanding = totalOutstanding.getAndUpdate((total: Int) => if (total < maxConcurrent) total + 1 else total)
    if (previouslyOutstanding < maxConcurrent) {
      val promise = Promise[T]()
      run(WorkItem(() => f, ctx, promise))
    } else {
      if (queue.size + 1 > maxQueueLength) {
        Future.failed(new IllegalStateException(s"$name queue may not exceed $maxQueueLength entries"))
      } else {
        val promise = Promise[T]()
        queue.add(WorkItem(() => f, ctx, promise))
        promise.future
      }
    }
  }
}

/**
  * Allows serialized execution of futures based on a Key (specifically the Hash of that key).
  * Does not block any threads and will schedule any items on a parent queue, if supplied.
  */
case class KeyedLock[K](name: String, maxQueueLength: Int, parent: Option[WorkQueue] = None) {
  private val queues = Lock(mutable.HashMap.empty[K, WorkQueue])

  def blocking[T](key: K)(f: => T)(implicit ctx: ExecutionContext): Future[T] = {
    apply(key)(Future(concurrent.blocking(f)))
  }

  def apply[T](key: K)(f: => Future[T])(implicit ctx: ExecutionContext): Future[T] = {
    queues(_.getOrElseUpdate(key, WorkQueue(s"$name-$key", maxConcurrent = 1, maxQueueLength, parent))(f))
  }
}
