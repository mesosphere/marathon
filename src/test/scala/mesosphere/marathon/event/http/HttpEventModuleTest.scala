package mesosphere.marathon.event.http

import mesosphere.marathon.MarathonSpec
import org.rogach.scallop.ScallopConf

class HttpEventModuleTest extends MarathonSpec {
  test("--http_endpoints accepts just one endpoint") {
    val conf = makeHttpEventConfig(
      "--http_endpoints", "http://127.0.0.1:8000"
    )

    assert(conf.httpEventEndpoints.get == Some(List("http://127.0.0.1:8000")))
  }

  test("--http_endpointss correctly splits multiple endpoints") {
    val conf = makeHttpEventConfig(
      "--http_endpoints", "http://127.0.0.1:8000,http://127.0.0.1:8001"
    )

    assert(conf.httpEventEndpoints.get == Some(List("http://127.0.0.1:8000", "http://127.0.0.1:8001")))
  }

  test("--http_endpoints trims endpoints") {
    val conf = makeHttpEventConfig(
      "--http_endpoints", "http://127.0.0.1:8000 , http://127.0.0.1:8001   "
    )

    assert(conf.httpEventEndpoints.get == Some(List("http://127.0.0.1:8000", "http://127.0.0.1:8001")))
  }

  def makeHttpEventConfig(args: String*): HttpEventConfiguration = {
    val opts = new ScallopConf(args) with HttpEventConfiguration {
      // scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
    }
    opts.afterInit()
    opts
  }
}
