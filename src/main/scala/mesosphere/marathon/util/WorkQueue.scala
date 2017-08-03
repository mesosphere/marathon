package mesosphere.marathon
package util

import com.typesafe.scalalogging.StrictLogging
import scala.collection.concurrent.TrieMap

import scala.concurrent.{ ExecutionContext, Future, Promise }

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
  */
case class WorkQueue(name: String, maxConcurrent: Int, maxQueueLength: Int) extends StrictLogging {
  require(maxConcurrent > 0 && maxQueueLength >= 0)

  private case class WorkItem[T](f: () => Future[T], ctx: ExecutionContext, promise: Promise[T])

  // Our queue of work. We synchronize on the whole class so this queue does not have to be threadsafe.
  private val queue = mutable.Queue[WorkItem[_]]()

  // Number of open work slots. This work queue is not using worker threads but triggers the next future once one
  // finishes. If now slot is left we queue. If no work is left we open up a slot.
  private var openSlotsCount: Int = maxConcurrent

  /**
    * Runs future that is wrapped in the work item.
    *
    * When the work item finished processing we execute the next if one is in the queue. Otherwise we just stop. A new
    * run might be triggered by [[WorkQueue.apply]].
    *
    * @param workItem
    * @tparam T
    * @return Future that completes when work item fished.
    */
  @SuppressWarnings(Array("CatchThrowable"))
  private def run[T](workItem: WorkItem[T]): Unit = synchronized {
    workItem.ctx.execute(new Runnable {
      override def run(): Unit = {
        try {
          val future = workItem.f()
          future.onComplete { _ =>
            // This might block for a short time if something is put into the queue. This is fine for two reasons
            // * The time it takes to complete apply() is very short.
            // * The blocking does not take place in this thread. So we won't deadlock.
            executeNextIfPossible()
          }(workItem.ctx)
          workItem.promise.completeWith(future)
        } catch {
          case ex: Throwable =>
            workItem.promise.failure(ex)
            executeNextIfPossible()
        }
      }
    })
  }

  /**
    * Executes the next work item or opens up a work slot for [[WorkQueue.apply]].
    *
    * If the queue is empty we free up a slot by decrementing the count.
    * If the queue is not empty we use our current slot to process the next item. The slot stays blocked and thus the
    * open slots count does not change.
    */
  private def executeNextIfPossible(): Unit = synchronized {
    if (queue.isEmpty) {
      openSlotsCount += 1
    } else {
      run(queue.dequeue())
    }
  }

  /**
    * Put work into the queue.
    *
    * @param f Future that is executed. Note that it's passed by name.
    * @param ctx
    * @tparam T
    * @return Future that completes when f completed.
    */
  def apply[T](f: => Future[T])(implicit ctx: ExecutionContext): Future[T] = synchronized {
    if (openSlotsCount > 0) {
      // We have an open slot so start processing the work immediately.
      openSlotsCount -= 1
      val promise = Promise[T]()
      run(WorkItem(() => f, ctx, promise))
      promise.future
    } else {
      // No work slot is left. Let's queue the work if possible.
      if (queue.size + 1 > maxQueueLength) {
        logger.warn(s"$name queue exceeded $maxQueueLength")
        Future.failed(new IllegalStateException(s"$name queue may not exceed $maxQueueLength entries"))
      } else {
        val promise = Promise[T]()
        queue += WorkItem(() => f, ctx, promise)
        promise.future
      }
    }
  }
}

/**
  * Allows serialized execution of futures based on a Key (specifically the Hash of that key).
  * Does not block any threads.
  */
case class KeyedLock[K](name: String, maxQueueLength: Int) {
  private val queues = TrieMap.empty[K, WorkQueue]

  /**
    * Create a WorkQueue by the provided key if it does not exist
    *
    * May create an additional workQueue that isn't used in the event of collision
    *
    * WorkQueues are not removed when empty
    */
  def apply[T](key: K)(f: => Future[T])(implicit ctx: ExecutionContext): Future[T] = {
    val workQueue = queues.getOrElseUpdate(key, WorkQueue(s"$name-$key", maxConcurrent = 1, maxQueueLength))
    workQueue(f)
  }
}
