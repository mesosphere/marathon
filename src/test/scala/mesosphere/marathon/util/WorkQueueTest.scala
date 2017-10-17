package mesosphere.marathon
package util

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ CountDownLatch, Semaphore }

import mesosphere.UnitTest
import mesosphere.marathon.core.async.ExecutionContexts.global
import org.scalatest.Inside

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.Try

class WorkQueueTest extends UnitTest with Inside {
  "WorkQueue" should {
    "return a failure if the Future returning thunk throws an exception" in {
      val queue = WorkQueue("test", maxConcurrent = 1, maxQueueLength = Int.MaxValue)
      val r = queue { // linter:ignore UndesirableTypeInference
        throw new RuntimeException("le failure")
      }
      inside(Try(r.futureValue)) {
        case Failure(ex) =>
          ex.getCause.shouldBe(a[RuntimeException])
      }
    }

    "cap the maximum number of concurrent operations" in {
      val queue = WorkQueue("test", maxConcurrent = 1, maxQueueLength = Int.MaxValue)
      val sem = new Semaphore(0)
      val counter = new AtomicInteger(0)
      queue {
        Future {
          sem.acquire()
        }
      }
      val blocked = queue {
        Future {
          counter.incrementAndGet()
        }
      }
      counter.get() should equal(0)
      blocked.isReadyWithin(1.millis) should be(false)
      sem.release()
      blocked.futureValue should be(1)
      counter.get() should equal(1)
    }

    "complete the future with a failure if the queue is capped" in {
      val queue = WorkQueue("abc", maxConcurrent = 1, maxQueueLength = 0)
      val semaphore = new Semaphore(0)
      queue {
        Future {
          semaphore.acquire()
        }
      }

      intercept[IllegalStateException] {
        throw queue {
          Future {
            semaphore.acquire()
          }
        }.failed.futureValue
      }

    }

    "continue executing even when the previous job failed" in {
      val queue = WorkQueue("failures", 1, Int.MaxValue)
      queue(Future.failed(new Exception("Expected"))).failed.futureValue.getMessage should equal("Expected")
      queue(Future.successful(7)).futureValue should be(7)
    }

    "run all tasks asked" in {
      val queue = WorkQueue("huge", 1, Int.MaxValue)
      val counter = new AtomicInteger()
      val latch = new CountDownLatch(100)
      0.until(100).foreach { _ =>
        queue {
          Future {
            counter.incrementAndGet()
            latch.countDown()
          }
        }
      }
      latch.await()
      counter.get() should equal (100)
    }

  }
  "KeyedLock" should {
    "allow exactly one work item per key" in {
      val lock = KeyedLock[String]("abc", Int.MaxValue)
      val sem = new Semaphore(0)
      val counter = new AtomicInteger(0)
      val notBlocked = lock("1") {
        Future {
          sem.acquire()
          counter.incrementAndGet
        }
      }
      val blocked = lock("1") {
        Future {
          counter.incrementAndGet()
        }
      }

      counter.get() should equal(0)

      blocked.isReadyWithin(1.millis) should be(false)
      notBlocked.isReadyWithin(1.millis) should be(false)
      sem.release()

      notBlocked.futureValue should be(1)
      blocked.futureValue should be(2)
      counter.get() should equal(2)
    }
    "allow two work items on different keys" in {
      val lock = KeyedLock[String]("abc", Int.MaxValue)
      val sem = new Semaphore(0)
      lock("1") {
        Future {
          sem.acquire()
        }
      }
      lock("2")(Future.successful("done")).futureValue should equal("done")
      sem.release()
    }
    "run everything asked" in {
      val lock = KeyedLock[String]("abc", Int.MaxValue)
      val counter = new AtomicInteger()
      val latch = new CountDownLatch(100)
      0.until(100).foreach { i =>
        lock(s"abc-${i % 2}") {
          Future {
            counter.incrementAndGet()
            latch.countDown()
          }
        }
      }
      latch.await()
      counter.get() should equal (100)
    }
  }
}
