package mesosphere.marathon
package core.async

import java.time.{ Clock, Duration, Instant, ZoneId }
import java.util.concurrent.{ Executors, Semaphore }

import akka.Done
import akka.actor.{ Actor, ActorRef, Props }
import mesosphere.marathon.core.async.RunContext.Expired
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

case class GetTestContextValue(promise: Promise[Option[Int]])
case class Forward[T](to: ActorRef, msg: T)

class TestActor extends Actor {
  override def receive: Receive = {
    case GetTestContextValue(promise) =>
      promise.success(TestContext.value)
    case Forward(to, msg) =>
      to ! msg
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

      /* Akka 2.5.x no longer calls ExecutionContext.prepare(). This is because the method was deprecated and there is
       * not a replacement available.
       *
       * We are currently not using context propagation.
       */
      "propagate the context in schedule once" ignore {
        val promise = Promise[Option[Int]]
        TestContext.withContext(7) {
          scheduler.scheduleOnce(1.nano) {
            promise.success(TestContext.value)
          }(ExecutionContexts.global)
        }
        promise.future.futureValue.value should be(7)
      }
      "propagate the context in schedule multiple" ignore {
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
    "when used with akka actors" should {
      "propagate the context" in {
        val promise = Promise[Option[Int]]
        val ref = system.actorOf(Props(classOf[TestActor]))
        TestContext.withContext(11) {
          ref ! GetTestContextValue(promise)
        }
        promise.future.futureValue.value should be(11)

        val cleared = Promise[Option[Int]]
        ref ! GetTestContextValue(cleared)
        cleared.future.futureValue should be('empty)
      }
      "between actors" in {
        val promise = Promise[Option[Int]]
        val ref = system.actorOf(Props(classOf[TestActor]))
        val ref2 = system.actorOf(Props(classOf[TestActor]))
        TestContext.withContext(11) {
          ref ! Forward(ref2, GetTestContextValue(promise))
        }
        promise.future.futureValue.value should be(11)

        val cleared = Promise[Option[Int]]
        ref ! Forward(ref2, GetTestContextValue(cleared))
        cleared.future.futureValue should be('empty)
      }
    }
  }
}

class RunContextTest extends UnitTest {
  implicit val FixedClock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

  "CancelContext" should {
    "Return running when there is no context" in {
      RunContext.state should be(RunContext.Running)
    }
    "Do nothing when cancel is called and there is no context" in {
      RunContext.cancel(false)
      RunContext.state should be(RunContext.Running)
    }
    "Do nothing when cancel is called with rollback and there is no context" in {
      RunContext.cancel(true)
      RunContext.state should be(RunContext.Running)
    }
    "Set cancellation when there is a context and cancel was requested" in {
      RunContext.withContext() {
        RunContext.cancel(false)
        RunContext.state should be(RunContext.Cancelled)
      }
      RunContext.state should be(RunContext.Running)
    }
    "Set rollback when there is a context and rollback was requested" in {
      RunContext.withContext() {
        RunContext.cancel(true)
        RunContext.state should be(RunContext.Rollback)
      }
      RunContext.state should be(RunContext.Running)
    }
    "Restore when there is an exception" in {
      intercept[Exception] {
        RunContext.withContext() {
          throw new Exception("")
        }
      }
      RunContext.state should be(RunContext.Running)
    }
    "Clear the context should restore it" in {
      RunContext.withContext() {
        RunContext.cancel(true)
        RunContext.clearContext {
          RunContext.state
        }
      } should be(RunContext.Running)
    }
    "Propagate Cancellation across async calls" in {
      val sem = new Semaphore(0)
      val promise = Promise[RunContext.RunState]()

      RunContext.withContext() {
        Future {
          sem.acquire()
          promise.success(RunContext.state)
        }(ExecutionContexts.global)

        RunContext.cancel(true)
        sem.release()
      }

      promise.future.futureValue should be(RunContext.Rollback)
    }
    "Cancelling a parent context will cancel a child context" in {
      val sem = new Semaphore(0)
      val promise = Promise[RunContext.RunState]()

      RunContext.withContext() {
        RunContext.withContext() {
          Future {
            sem.acquire()
            promise.success(RunContext.state)
          }(ExecutionContexts.global)
        }

        RunContext.cancel(true)
        sem.release()
      }

      promise.future.futureValue should be(RunContext.Rollback)
    }
    "Cancelling a child context will not cancel the parent" in {
      val sem = new Semaphore(0)
      val promise = Promise[RunContext.RunState]()

      RunContext.withContext() {
        RunContext.withContext() {
          Future {
            sem.acquire()
            promise.success(RunContext.state)
          }(ExecutionContexts.global)
          RunContext.cancel(false)
          sem.release()
        }
        RunContext.state
      } should equal(RunContext.Running)

      promise.future.futureValue should be(RunContext.Cancelled)
    }
    "when working with deadlines" should {
      def getDeadline(): Option[Instant] = Context.get(Context.Run).map(_.deadline).filterNot(_ == Instant.MAX)

      "Return not expired when there is a deadline and it hasn't expired." in {
        val clock = new SettableClock()
        RunContext.withContext(Instant.now(clock).plus(Duration.ofMinutes(1L))) {
          RunContext.state()(clock)
        } should be(RunContext.Running)
      }
      "Restore outside of the context" in {
        RunContext.withContext(Instant.now(FixedClock))(Done)
        getDeadline() should be('empty)
      }
      "Return expired when there is a deadline and it has expired" in {
        val clock = new SettableClock()
        val expireAt = Instant.now(clock).plus(Duration.ofMinutes(1L))
        RunContext.withContext(expireAt) {
          clock.plus(Duration.ofMinutes(3))
          RunContext.state()(clock)
        } should be(Expired(expireAt))
      }
      "Keep the parent deadline if the parent's deadline is sooner than the child" in {
        val parentDeadline = Instant.now(FixedClock).plus(Duration.ofMinutes(1L))
        RunContext.withContext(parentDeadline) {
          RunContext.withContext(Instant.now(FixedClock).plus(Duration.ofMinutes(2L))) {
            getDeadline()
          }
        }.value should equal(parentDeadline)
      }
      "Clearing will restore the old deadline" in {
        val outerDeadline = Instant.now(FixedClock).minus(Duration.ofMinutes(1L))
        RunContext.withContext(outerDeadline) {
          RunContext.clearContext(getDeadline()) should be('empty)
          getDeadline()
        }.value should equal(outerDeadline)
      }
    }
  }
}
