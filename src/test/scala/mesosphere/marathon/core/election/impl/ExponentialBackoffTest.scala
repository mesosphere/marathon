package mesosphere.marathon
package core.election.impl

import mesosphere.AkkaUnitTest

import scala.language.postfixOps
import scala.concurrent.Future
import scala.concurrent.duration._

class ExponentialBackoffTest extends AkkaUnitTest {

  "ExponentialBackoff" when {

    "used by several threads" should {

      "not lead to dead locks under any circumstance" in {

        val backoff = new ExponentialBackoff()

        object sharedMonitored

        val fa = Future {
          Thread.sleep(10)
          sharedMonitored.synchronized {
            backoff.increase() // invoking `increase` will try to acquire backoff's monitor lock
          }
        }

        val fb = Future {
          /* Backoff monitor could inadvertently adcquired */
          backoff.synchronized {
            Thread.sleep(20)
            sharedMonitored.synchronized {
              ()
            }
          }
        }

        val fComposed = for (_ <- fa; _ <- fb) yield ()

        assert(fComposed.isReadyWithin(50 milliseconds))

      }

    }

    "requested to increase durations" should {

      val backoff: Backoff = new ExponentialBackoff(0.5 seconds, 16 seconds)

      "produce monotonic values" in {

        val durations = (0 to 5) map { _ =>
          val v = backoff.value()
          backoff.increase()
          v
        }

        durations shouldBe Seq(
          0.5 seconds, 1 seconds, 2 seconds, 4 seconds, 8 seconds, 16 seconds
        )

      }

      "do not exceed the maximum duration" in {

        backoff.increase()

        backoff.value() shouldBe (16 seconds)

      }

    }

  }

}
