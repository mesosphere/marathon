package mesosphere.marathon
package core.event

import mesosphere.UnitTest
import org.rogach.scallop.{ ScallopConf, ScallopOption }

import scala.concurrent.duration.FiniteDuration

class EventConfTests extends UnitTest {
  "EventConf" should {
    "--http_endpoints accepts just one endpoint" in {
      val conf = makeEventConf(
        "--http_endpoints", "http://127.0.0.1:8000"
      )

      conf.httpEventEndpoints.get should be (Some(List("http://127.0.0.1:8000")))
    }

    "--http_endpoints correctly splits multiple endpoints" in {
      val conf = makeEventConf(
        "--http_endpoints", "http://127.0.0.1:8000,http://127.0.0.1:8001"
      )

      conf.httpEventEndpoints.get should be (Some(List("http://127.0.0.1:8000", "http://127.0.0.1:8001")))
    }

    "--http_endpoints trims endpoints" in {
      val conf = makeEventConf(
        "--http_endpoints", "http://127.0.0.1:8000 , http://127.0.0.1:8001   "
      )

      conf.httpEventEndpoints.get should be (Some(List("http://127.0.0.1:8000", "http://127.0.0.1:8001")))
    }
  }
  def makeEventConf(args: String*): EventConf = {
    new ScallopConf(args) with EventConf {
      // scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
      verify()

      override def zkTimeoutDuration: FiniteDuration = ???

      override def hostname: ScallopOption[String] = opt[String](
        "hostname",
        descr = "mock",
        default = Some("localhost")
      )
    }
  }
}
