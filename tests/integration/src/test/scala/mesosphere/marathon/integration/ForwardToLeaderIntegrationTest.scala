package mesosphere.marathon
package integration

import akka.http.scaladsl.model.ContentTypes
import java.net.URL
import mesosphere.marathon.api.forwarder.RequestForwarder
import org.apache.commons.io.IOUtils
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.api.LeaderProxyFilter
import mesosphere.marathon.integration.setup._
import mesosphere.util.PortAllocator
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.time.{Milliseconds, Seconds, Span}

/**
  * Tests forwarding requests.
  */
class ForwardToLeaderIntegrationTest extends AkkaIntegrationTest with TableDrivenPropertyChecks {

  val forwarderStartTimeout = PatienceConfiguration.Timeout(Span(60, Seconds))
  val forwarderStartInterval = PatienceConfiguration.Interval(Span(100, Milliseconds))

  val cases = Table(("Asynchronous"), (true), (false))

  forAll(cases) { async =>
    def withForwarder[T](testCode: ForwarderService => T): T = {
      val forwarder = new ForwarderService(async)
      try {
        testCode(forwarder)
      } finally {
        forwarder.close()
      }
    }

    s"ForwardingToLeader (async = ${async})" should {
      "direct ping" in withForwarder { forwarder =>
        val helloApp = forwarder.startHelloApp()
        helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
        val appFacade = new AppMockFacade()
        val result = appFacade.ping("localhost", port = helloApp.port).futureValue
        result should be(OK)
        result.entityString should be("pong\n")
        result.value.headers.exists(_.name == RequestForwarder.HEADER_VIA) should be(false)
        result.value.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) should be(1)
        result.value.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value should be(s"http://localhost:${helloApp.port}")
      }

      "forward ping" in withForwarder { forwarder =>
        val helloApp = forwarder.startHelloApp()
        helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
        val forwardApp = forwarder.startForwarder(helloApp.port)
        forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"

        val appFacade = new AppMockFacade()
        val result = appFacade.ping("localhost", port = forwardApp.port).futureValue
        result should be(OK)
        result.entityString should be("pong\n")
        result.value.headers.count(_.name == RequestForwarder.HEADER_VIA) should be(1)
        result.value.headers.find(_.name == RequestForwarder.HEADER_VIA).get.value should be(s"1.1 localhost:${forwardApp.port}")
        result.value.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) should be(1)
        result.value.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value should be(s"http://localhost:${helloApp.port}")
      }

      "direct HTTPS ping" in withForwarder { forwarder =>
        val helloApp = forwarder.startHelloApp("--https_port", Seq(
          "--disable_http",
          "--ssl_keystore_path", SSLContextTestUtil.selfSignedKeyStorePath,
          "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
          "--https_address", "localhost"))
        helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"

        val pingURL = new URL(s"https://localhost:${helloApp.port}/ping")
        val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.selfSignedSSLContext)
        val via = connection.getHeaderField(RequestForwarder.HEADER_VIA)
        val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
        val response = IOUtils.toString(connection.getInputStream, "UTF-8")
        response should be("pong\n")
        via should be(null)
        leader should be(s"https://localhost:${helloApp.port}")
      }

      "forwarding HTTPS ping with a self-signed cert" in withForwarder { forwarder =>
        val helloApp = forwarder.startHelloApp("--https_port", Seq(
          "--disable_http",
          "--ssl_keystore_path", SSLContextTestUtil.selfSignedKeyStorePath,
          "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
          "--https_address", "localhost"))
        helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"

        val forwardApp = forwarder.startForwarder(helloApp.port, "--https_port", args = Seq(
          "--disable_http",
          "--ssl_keystore_path", SSLContextTestUtil.selfSignedKeyStorePath,
          "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
          "--https_address", "localhost"))
        forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"

        val pingURL = new URL(s"https://localhost:${forwardApp.port}/ping")
        val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.selfSignedSSLContext)
        val via = connection.getHeaderField(RequestForwarder.HEADER_VIA)
        val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
        val response = IOUtils.toString(connection.getInputStream, "UTF-8")
        response should be("pong\n")
        via should be(s"1.1 localhost:${forwardApp.port}")
        leader should be(s"https://localhost:${helloApp.port}")
      }

      "forwarding HTTPS ping with a ca signed cert" in withForwarder { forwarder =>
        val helloApp = forwarder.startHelloApp("--https_port", Seq(
          "--disable_http",
          "--ssl_keystore_path", SSLContextTestUtil.caKeyStorePath,
          "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
          "--https_address", "localhost"))
        helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"

        val forwardApp = forwarder.startForwarder(
          helloApp.port,
          "--https_port",
          args = Seq(
            "--disable_http",
            "--ssl_keystore_path", SSLContextTestUtil.caKeyStorePath,
            "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
            "--https_address", "localhost"))

        forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"
        val forwardPort = forwardApp.port

        val pingURL = new URL(s"https://localhost:${forwardPort}/ping")
        val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.caSignedSSLContext)
        val via = connection.getHeaderField(RequestForwarder.HEADER_VIA)
        val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
        val response = IOUtils.toString(connection.getInputStream, "UTF-8")
        response should be("pong\n")
        via should be(s"1.1 localhost:$forwardPort")
        leader should be(s"https://localhost:${helloApp.port}")
      }

      "direct 404" in withForwarder { forwarder =>
        val helloApp = forwarder.startHelloApp()
        helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
        val appFacade = new AppMockFacade()
        val result = appFacade.custom("/notfound")("localhost", port = helloApp.port).futureValue
        result should be(NotFound)
      }

      "forwarding 404" in withForwarder { forwarder =>
        val helloApp = forwarder.startHelloApp()
        helloApp.launched.futureValue
        val forwardApp = forwarder.startForwarder(helloApp.port)
        forwardApp.launched.futureValue
        val appFacade = new AppMockFacade()
        val result = appFacade.custom("/notfound")("localhost", port = forwardApp.port).futureValue
        result should be(NotFound)
      }

      "direct internal server error" in withForwarder { forwarder =>
        val helloApp = forwarder.startHelloApp()
        helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
        val appFacade = new AppMockFacade()
        val result = appFacade.custom("/hello/crash")("localhost", port = helloApp.port).futureValue
        result should be(ServerError)
        result.entityString should be("Error")
      }

      "forwarding internal server error" in withForwarder { forwarder =>
        val helloApp = forwarder.startHelloApp()
        helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
        val forwardApp = forwarder.startForwarder(helloApp.port)
        forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"
        val appFacade = new AppMockFacade()
        val result = appFacade.custom("/hello/crash")("localhost", port = forwardApp.port).futureValue
        result should be(ServerError)
        result.entityString should be("Error")
      }

      "forwarding connection failed" in withForwarder { forwarder =>
        val forwardApp = forwarder.startForwarder(PortAllocator.ephemeralPort())
        forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"
        val appFacade = new AppMockFacade()
        val result = appFacade.ping("localhost", port = forwardApp.port).futureValue
        result should be(BadGateway)
      }

      "forwarding loop" in withForwarder { forwarder =>
        val forwardApp1 = forwarder.startForwarder(PortAllocator.ephemeralPort())
        forwardApp1.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"
        val forwardApp2 = forwarder.startForwarder(PortAllocator.ephemeralPort())
        forwardApp2.launched.futureValue

        val appFacade = new AppMockFacade()
        val result = appFacade.ping("localhost", port = forwardApp1.port).futureValue
        result should be(BadGateway)
      }

      "returning content type" in withForwarder { forwarder =>
        val helloApp = forwarder.startHelloApp()
        helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
        val forwardApp = forwarder.startForwarder(helloApp.port)
        forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"

        val appFacade = new AppMockFacade()
        val result = appFacade.custom("/json")("localhost", forwardApp.port).futureValue
        result should be(OK)
        result.entityString should be("{}")
        result.value.entity.contentType shouldBe (ContentTypes.`application/json`)
      }

      "redirect a request to /v2/events to a leader" in withForwarder { forwarder =>
        val helloApp = forwarder.startHelloApp()
        helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
        val forwardApp = forwarder.startForwarder(helloApp.port)
        forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"

        val appFacade = new AppMockFacade()
        val leaderResult = appFacade.custom("/v2/events")("localhost", port = helloApp.port).futureValue
        val forwardResult = appFacade.custom("/v2/events")("localhost", port = forwardApp.port).futureValue

        leaderResult should be(OK)
        leaderResult.entityString should be("events")

        forwardResult should be(Redirect)
      }

      "forward a request to /v/2events when redirection is disabled" in withForwarder { forwarder =>
        val helloApp = forwarder.startHelloApp()
        helloApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
        val forwardApp = forwarder.startForwarder(helloApp.port, args = Seq("--disable_events_redirection"))
        forwardApp.launched.futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"

        val appFacade = new AppMockFacade()
        val result = appFacade.custom("/v2/events")("localhost", port = forwardApp.port).futureValue
        result should be(OK)
        result.entityString should be("events")
        result.value.headers.count(_.name == RequestForwarder.HEADER_VIA) should be(1)
        result.value.headers.find(_.name == RequestForwarder.HEADER_VIA).get.value should be(s"1.1 localhost:${forwardApp.port}")
        result.value.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) should be(1)
        result.value.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value should be(s"http://localhost:${helloApp.port}")
      }
    }
  }
}
