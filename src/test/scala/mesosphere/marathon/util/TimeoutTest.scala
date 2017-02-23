package mesosphere.marathon
package util

import mesosphere.AkkaUnitTest

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
    }
  }
}
