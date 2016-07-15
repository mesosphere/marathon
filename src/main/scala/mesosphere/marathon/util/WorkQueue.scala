package mesosphere.marathon.util

import java.util

import scala.concurrent.{ ExecutionContext, Future, Promise }

/**
  * Class that limits the number of concurrent tasks, queuing the tasks for future execution.
  *
  * @param maxConcurrent The maximum number of concurrent tasks
  * @param ctx The execution context to execute the tasks on.
  */
case class WorkQueue(maxConcurrent: Int)(implicit ctx: ExecutionContext) {
  require(maxConcurrent > 0)
  private[util] val lock = RichLock()
  private[util] var outstandingTasks = 0
  private[util] val queue = new util.ArrayDeque[(() => Future[Any], Promise[Any])]

  private def runPending(): Unit = {
    lock {
      while (outstandingTasks < maxConcurrent && queue.size() > 0) {
        val (f, promise) = queue.remove()
        outstandingTasks += 1
        runItem(f, promise)
      }
    }
  }

  private def runItem[T](f: () => Future[T], promise: Promise[T]): Unit = {
    ctx.execute(new Runnable {
      override def run(): Unit = {
        promise.completeWith(f())
        lock {
          outstandingTasks -= 1
          runPending()
        }
      }
    })
  }

  /**
    * Execute the given task on the execution context, queuing it for eventual execution
    * if there are already the given number of concurrent tasks running.
    *
    * @param f a method to execute, eventually/
    * @return A *new* future representing when the task executed.
    */
  def execute[T](f: () => Future[T]): Future[T] = {
    lock {
      if (outstandingTasks < maxConcurrent) {
        outstandingTasks += 1
        val promise = Promise[T]()
        runItem(f, promise)
        promise.future
      } else {
        val promise = Promise[T]()
        queue.add((f.asInstanceOf[() => Future[Any]], promise.asInstanceOf[Promise[Any]]))
        promise.future
      }
    }
  }

  /**
    * Alias for [[WorkQueue.execute]] that allows call-by-name
    */
  def apply[T](f: => Future[T]): Future[T] = execute(() => f)
}
