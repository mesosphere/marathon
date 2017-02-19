package mesosphere.marathon.core.election.impl

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Matchers, WordSpec}

import scala.language.postfixOps
import scala.concurrent.Future
import scala.concurrent.duration._

class ExponentialBackoffTest extends WordSpec with Matchers with ScalaFutures {

  import scala.concurrent.ExecutionContext.Implicits.global

  "ExponentialBackoff" when  {

    "used by several threads" should {

      "not lead to dead locks" in {

        val backoff = new ExponentialBackoff()

        object sharedMonitored

        val fa = Future {
          Thread.sleep(10)
          sharedMonitored.synchronized {
            backoff.increase()
          }
        }

        val fb = Future {
          backoff.synchronized {
            Thread.sleep(20)
            sharedMonitored.synchronized {
              ()
            }
          }
        }

        val fComposed = for(_ <- fa; _ <- fb) yield ()

        assert(fComposed.isReadyWithin(50 milliseconds))

      }

    }

  }

}
