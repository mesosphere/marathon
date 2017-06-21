package mesosphere.marathon
package util

import java.time.Instant

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.async.RunContext.Expired
import mesosphere.marathon.core.async.{ ExecutionContexts, RunContext }
import mesosphere.marathon.test.SettableClock

import scala.concurrent.Future
import scala.concurrent.duration._

class TimeoutTest extends AkkaUnitTest {
  "Timeout" when {
    "async" should {
      "complete" in {
        Timeout(1.second)(Future.successful(1)).futureValue should equal(1)
      }
      "fail if the method fails" in {
        val failure = Timeout(1.second)(Future.failed(new IllegalArgumentException())).failed.futureValue
        failure shouldBe a[IllegalArgumentException]
      }
      "fail with a timeout exception if the method took too long" in {
        val failure = Timeout(1.milli)(Future(Thread.sleep(1000))).failed.futureValue
        failure shouldBe a[TimeoutException]
      }
      "set the deadline" in {
        implicit val clock = new SettableClock()

        Timeout(1.second) {
          clock.plus(2.second)
          Future { RunContext.state() }(ExecutionContexts.global)
        }.futureValue should be(Expired(Instant.now(clock).minusSeconds(2)))
      }
    }
    "blocking" should {
      "complete" in {
        Timeout.blocking(1.second)(1).futureValue should equal(1)
      }
      "fail if the method fails" in {
        val failure = Timeout.blocking(1.second)(throw new IllegalArgumentException).failed.futureValue
        failure shouldBe a[IllegalArgumentException]
      }
      "fail with a timeout if the method took too long" in {
        val failure = Timeout.blocking(1.milli)(Thread.sleep(1000)).failed.futureValue
        failure shouldBe a[TimeoutException]
      }
      "set the deadline" in {
        implicit val clock = new SettableClock()

        Timeout.blocking(1.second) {
          clock.plus(2.second)
          RunContext.state()(clock)
        }(scheduler, ExecutionContexts.global, clock).futureValue should be(Expired(Instant.now(clock).minusSeconds(2)))
      }
    }
  }
}
