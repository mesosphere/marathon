package mesosphere.marathon.util

import mesosphere.AkkaUnitTest

import scala.concurrent.Future
import scala.concurrent.duration._

class TimeoutTest extends AkkaUnitTest {
  implicit val scheduler = system.scheduler
  implicit val ctx = system.dispatcher

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
        val failure = Timeout(1.nano)(Future(Thread.sleep(50))).failed.futureValue
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
        val failure = Timeout.blocking(1.nano)(Thread.sleep(50)).failed.futureValue
        failure shouldBe a[TimeoutException]
      }
    }
    "unsafe" should {
      "complete" in {
        Timeout.unsafe(1.second)(Future.successful(1)).futureValue should equal(1)
      }
      "fail if the method fails" in {
        val failure = Timeout.unsafe(1.second)(Future.failed(new IllegalArgumentException)).failed.futureValue
        failure shouldBe a[IllegalArgumentException]
      }
      "fail with a timeout if the method took too long" in {
        val failure = Timeout.unsafe(1.nano)(Future(Thread.sleep(50))).failed.futureValue
        failure shouldBe a[TimeoutException]
      }
    }
    "unsafe blocking" should {
      "complete" in {
        Timeout.unsafeBlocking(1.second)(1).futureValue should equal(1)
      }
      "fail if the method fails" in {
        val failure = Timeout.unsafeBlocking(1.second)(throw new IllegalArgumentException).failed.futureValue
        failure shouldBe a[IllegalArgumentException]
      }
      "fail with a timeout if the method took too long" in {
        val failure = Timeout.unsafeBlocking(1.nano)(Thread.sleep(50)).failed.futureValue
        failure shouldBe a[TimeoutException]
      }
    }
  }
}
