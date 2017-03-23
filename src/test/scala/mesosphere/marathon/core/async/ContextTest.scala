package mesosphere.marathon
package core.async

import java.time.{ Clock, Duration, Instant, ZoneId }
import java.util.concurrent.{ Executors, Semaphore }

import akka.Done
import mesosphere.marathon.test.SettableClock
import mesosphere.{ AkkaUnitTest, UnitTest }

import scala.concurrent.{ ExecutionContext, Future, Promise }

object TestContext {
  def value: Option[Int] = Context.get(Context.TestContext)

  def withContext[T](value: Int)(f: => T): T = {
    val old = Context.get(Context.TestContext)
    Context.put(Context.TestContext, value)
    try {
      f
    } finally {
      old.fold(Context.remove(Context.TestContext))(Context.put(Context.TestContext, _))
    }
  }
}

class ContextTest extends UnitTest {
  "Context" should {
    "clear context should store/restore the context" in {
      TestContext.withContext(7) {
        Context.clearContext(TestContext.value) should be('empty)
        TestContext.value.value should be(7)
      }
      TestContext.value should be('empty)
    }
    "propagate the context" in {
      TestContext.withContext(7) {
        TestContext.value
      }.value should be(7)
      TestContext.value should be('empty)
    }
    "restore after a throw" in {
      TestContext.withContext(5) {
        intercept[Exception] {
          TestContext.withContext(7)(throw new Exception("expected"))
        }
        TestContext.value.value should be(5)
      }
      TestContext.value should be('empty)
    }
  }
}

class ContextPropagatingExecutionContextTest extends AkkaUnitTest {
  def withSingleThreadFixture[T](f: ExecutionContext => T): Unit = {
    val executor = Executors.newSingleThreadExecutor()
    try {
      val ctx = ContextPropagatingExecutionContextWrapper(ExecutionContext.fromExecutor(executor))
      f(ctx)
    } finally {
      executor.shutdown()
    }
  }
  "ContextPropagatingExecutionContext" should {
    "propagate the result across threads" in {
      TestContext.withContext(7) {
        val future = Future {
          TestContext.value
        }(ExecutionContexts.global)
        future.futureValue.value should be(7)
      }
    }
    "restore the context" in withSingleThreadFixture { implicit ctx =>
      TestContext.withContext(7) {
        Future {
          TestContext.value
        }
      }
      val promise = Promise[Option[Int]]()
      ctx.execute(new Runnable {
        override def run(): Unit = promise.success(TestContext.value)
      })
      promise.future.futureValue should be('empty)
    }
    "restore the context even if it throws." in withSingleThreadFixture { implicit ctx =>
      TestContext.withContext(7) {
        Future {
          throw new Exception("Expected")
        }
      }
      val promise = Promise[Option[Int]]()
      ctx.execute(new Runnable {
        override def run(): Unit = promise.success(TestContext.value)
      })
      promise.future.futureValue should be('empty)
    }
    // validate some assumptions about the akka scheduler.
    "when scheduling via akka" should {
      import scala.concurrent.duration._

      "propagate the context in schedule once" in {
        val promise = Promise[Option[Int]]
        TestContext.withContext(7) {
          scheduler.scheduleOnce(1.nano) {
            promise.success(TestContext.value)
          }(ExecutionContexts.global)
        }
        promise.future.futureValue.value should be(7)
      }
      "propagate the context in schedule multiple" in {
        val promise = Promise[Option[Int]]()

        TestContext.withContext(7) {
          val cancelToken = scheduler.schedule(1.nano, 1.day) {
            promise.success(TestContext.value)
          }(ExecutionContexts.global)
          promise.future.futureValue.value should be(7)
          cancelToken.cancel()
        }
      }
    }
  }
}

class CancelContextTest extends UnitTest {
  "CancelContext" should {
    "Return running when there is no context" in {
      CancelContext.state should be(CancelContext.Running)
    }
    "Do nothing when cancel is called and there is no context" in {
      CancelContext.cancel(false)
      CancelContext.state should be(CancelContext.Running)
    }
    "Do nothing when cancel is called with rollback and there is no context" in {
      CancelContext.cancel(true)
      CancelContext.state should be(CancelContext.Running)
    }
    "Set cancellation when there is a context and cancel was requested" in {
      CancelContext.withContext {
        CancelContext.cancel(false)
        CancelContext.state should be(CancelContext.Cancelled)
      }
      CancelContext.state should be(CancelContext.Running)
    }
    "Set rollback when there is a context and rollback was requested" in {
      CancelContext.withContext {
        CancelContext.cancel(true)
        CancelContext.state should be(CancelContext.Rollback)
      }
      CancelContext.state should be(CancelContext.Running)
    }
    "Restore when there is an exception" in {
      intercept[Exception] {
        CancelContext.withContext {
          throw new Exception("")
        }
      }
      CancelContext.state should be(CancelContext.Running)
    }
    "Clear the context should restore it" in {
      CancelContext.withContext {
        CancelContext.cancel(true)
        CancelContext.clearContext {
          CancelContext.state
        }
      } should be(CancelContext.Running)
    }
    "Propagate Cancellation across async calls" in {
      val sem = new Semaphore(0)
      val promise = Promise[CancelContext.CancelState]()

      CancelContext.withContext {
        Future {
          sem.acquire()
          promise.success(CancelContext.state)
        }(ExecutionContexts.global)

        CancelContext.cancel(true)
        sem.release()
      }

      promise.future.futureValue should be(CancelContext.Rollback)
    }
    "Cancelling a parent context will cancel a child context" in {
      val sem = new Semaphore(0)
      val promise = Promise[CancelContext.CancelState]()

      CancelContext.withContext {
        CancelContext.withContext {
          Future {
            sem.acquire()
            promise.success(CancelContext.state)
          }(ExecutionContexts.global)
        }

        CancelContext.cancel(true)
        sem.release()
      }

      promise.future.futureValue should be(CancelContext.Rollback)
    }
    "Cancelling a child context will not cancel the parent" in {
      val sem = new Semaphore(0)
      val promise = Promise[CancelContext.CancelState]()

      CancelContext.withContext {
        CancelContext.withContext {
          Future {
            sem.acquire()
            promise.success(CancelContext.state)
          }(ExecutionContexts.global)
          CancelContext.cancel(false)
          sem.release()
        }
        CancelContext.state
      } should equal(CancelContext.Running)

      promise.future.futureValue should be(CancelContext.Cancelled)
    }
  }
}

class DeadlineContextTest extends UnitTest {
  implicit val FixedClock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

  def getDeadline(): Option[Instant] = Context.get(Context.Deadline).map(_.deadline)

  "DeadlineContext" should {
    "Return not expired when there is no deadline" in {
      DeadlineContext.isExpired() should be(false)
    }
    "Return not expired when there is a deadline and it hasn't expired." in {
      val clock = new SettableClock()
      DeadlineContext.withDeadline(Instant.now(clock).plus(Duration.ofMinutes(1L))) {
        DeadlineContext.isExpired()(clock)
      } should be(false)
    }
    "Restore outside of the context" in {
      DeadlineContext.withDeadline(Instant.now(FixedClock))(Done)
      getDeadline() should be('empty)
    }
    "Return expired when there is a deadline and it has expired" in {
      val clock = new SettableClock()
      DeadlineContext.withDeadline(Instant.now(clock).plus(Duration.ofMinutes(1L))) {
        clock.plus(Duration.ofMinutes(3))
        DeadlineContext.isExpired()(clock)
      } should be(true)
    }
    "Keep the parent deadline if the parent's deadline is sooner than the child" in {
      val parentDeadline = Instant.now(FixedClock).plus(Duration.ofMinutes(1L))
      DeadlineContext.withDeadline(parentDeadline) {
        DeadlineContext.withDeadline(Instant.now(FixedClock).plus(Duration.ofMinutes(2L))) {
          getDeadline()
        }
      }.value should equal(parentDeadline)
    }
    "Clearing will restore the old deadline" in {
      val outerDeadline = Instant.now(FixedClock).minus(Duration.ofMinutes(1L))
      DeadlineContext.withDeadline(outerDeadline) {
        DeadlineContext.clearDeadline(getDeadline()) should be('empty)

        getDeadline()
      }.value should equal(outerDeadline)
    }
  }
}