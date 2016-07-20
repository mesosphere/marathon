package mesosphere.marathon.core.event

import mesosphere.marathon.MarathonSpec
import org.rogach.scallop.ScallopConf

import scala.concurrent.duration.FiniteDuration

class EventConfTests extends MarathonSpec {
  test("--http_endpoints accepts just one endpoint") {
    val conf = makeEventConf(
      "--http_endpoints", "http://127.0.0.1:8000"
    )

    assert(conf.httpEventEndpoints.get == Some(List("http://127.0.0.1:8000")))
  }

  test("--http_endpoints correctly splits multiple endpoints") {
    val conf = makeEventConf(
      "--http_endpoints", "http://127.0.0.1:8000,http://127.0.0.1:8001"
    )

    assert(conf.httpEventEndpoints.get == Some(List("http://127.0.0.1:8000", "http://127.0.0.1:8001")))
  }

  test("--http_endpoints trims endpoints") {
    val conf = makeEventConf(
      "--http_endpoints", "http://127.0.0.1:8000 , http://127.0.0.1:8001   "
    )

    assert(conf.httpEventEndpoints.get == Some(List("http://127.0.0.1:8000", "http://127.0.0.1:8001")))
  }

  def makeEventConf(args: String*): EventConf = {
    new ScallopConf(args) with EventConf {
      // scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
      verify()

      override def zkTimeoutDuration: FiniteDuration = ???
    }
  }
}
