package mesosphere.marathon.util

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

import mesosphere.UnitTest

import scala.concurrent.{ ExecutionContext, Future }

class WorkQueueTest extends UnitTest {
  import ExecutionContext.Implicits.global
  "WorkQueue" should {
    "limit the number of tasks" in {
      val queue = WorkQueue(1)
      val firstTaskRunning = new Semaphore(0)
      val firstTaskExit = new Semaphore(0)
      val secondTaskRunning = new Semaphore(0)
      val secondTaskExit = new Semaphore(0)
      val tasksRunning = new AtomicInteger(0)

      val task1 = queue {
        firstTaskRunning.release()
        tasksRunning.incrementAndGet()
        firstTaskExit.acquire()
        tasksRunning.decrementAndGet()
        Future.successful(1)
      }

      val task2 = queue {
        tasksRunning.incrementAndGet()
        secondTaskRunning.release()
        secondTaskExit.acquire()
        tasksRunning.decrementAndGet()
        Future.successful(2)
      }

      firstTaskRunning.acquire()
      secondTaskRunning.tryAcquire(1) should equal(false)
      tasksRunning.get() should equal(1)
      queue.lock {
        queue.outstandingTasks should equal(1)
        queue.queue.size should equal(1)
      }
      firstTaskExit.release()
      task1.futureValue should equal(1)

      secondTaskRunning.acquire()
      tasksRunning.get() should equal(1)
      secondTaskExit.release()
      task2.futureValue should equal(2)

      tasksRunning.get() should equal(0)

      queue.lock {
        queue.outstandingTasks should equal(0)
        queue.queue should be('empty)
      }
    }
  }
}
