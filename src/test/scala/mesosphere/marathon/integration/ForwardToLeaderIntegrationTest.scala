package mesosphere.marathon
package integration

import java.net.URL
import org.apache.commons.io.IOUtils
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.api.{ JavaUrlConnectionRequestForwarder, LeaderProxyFilter }
import mesosphere.marathon.integration.setup._
import mesosphere.util.PortAllocator
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.time.{ Milliseconds, Seconds, Span }

/**
  * Tests forwarding requests.
  */
@IntegrationTest
class ForwardToLeaderIntegrationTest extends AkkaIntegrationTest {
  def withForwarder[T](testCode: ForwarderService => T): T = {
    val forwarder = new ForwarderService
    try {
      testCode(forwarder)
    } finally {
      forwarder.close()
    }
  }

  val forwarderStartTimeout = PatienceConfiguration.Timeout(Span(60, Seconds))
  val forwarderStartInterval = PatienceConfiguration.Interval(Span(100, Milliseconds))

  "ForwardingToLeader" should {
    "direct ping" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp().futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
      val appFacade = new AppMockFacade()
      val result = appFacade.ping("localhost", port = helloPort).futureValue
      result should be(OK)
      result.entityString should be("pong\n")
      result.value.headers.exists(_.name == JavaUrlConnectionRequestForwarder.HEADER_VIA) should be(false)
      result.value.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) should be(1)
      result.value.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value should be(s"http://localhost:$helloPort")
    }

    "forward ping" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp().futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
      val forwardPort = forwarder.startForwarder(helloPort).futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"

      val appFacade = new AppMockFacade()
      val result = appFacade.ping("localhost", port = forwardPort).futureValue
      result should be(OK)
      result.entityString should be("pong\n")
      result.value.headers.count(_.name == JavaUrlConnectionRequestForwarder.HEADER_VIA) should be(1)
      result.value.headers.find(_.name == JavaUrlConnectionRequestForwarder.HEADER_VIA).get.value should be(s"1.1 localhost:$forwardPort")
      result.value.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) should be(1)
      result.value.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value should be(s"http://localhost:$helloPort")
    }

    "direct HTTPS ping" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp("--https_port", Seq(
        "--disable_http",
        "--ssl_keystore_path", SSLContextTestUtil.selfSignedKeyStorePath,
        "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
        "--https_address", "localhost")).futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"

      val pingURL = new URL(s"https://localhost:$helloPort/ping")
      val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.selfSignedSSLContext)
      val via = connection.getHeaderField(JavaUrlConnectionRequestForwarder.HEADER_VIA)
      val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
      val response = IOUtils.toString(connection.getInputStream, "UTF-8")
      response should be("pong\n")
      via should be(null)
      leader should be(s"https://localhost:$helloPort")
    }

    "forwarding HTTPS ping with a self-signed cert" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp("--https_port", Seq(
        "--disable_http",
        "--ssl_keystore_path", SSLContextTestUtil.selfSignedKeyStorePath,
        "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
        "--https_address", "localhost")).futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"

      val forwardPort = forwarder.startForwarder(helloPort, "--https_port", args = Seq(
        "--disable_http",
        "--ssl_keystore_path", SSLContextTestUtil.selfSignedKeyStorePath,
        "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
        "--https_address", "localhost")).futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"

      val pingURL = new URL(s"https://localhost:$forwardPort/ping")
      val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.selfSignedSSLContext)
      val via = connection.getHeaderField(JavaUrlConnectionRequestForwarder.HEADER_VIA)
      val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
      val response = IOUtils.toString(connection.getInputStream, "UTF-8")
      response should be("pong\n")
      via should be(s"1.1 localhost:$forwardPort")
      leader should be(s"https://localhost:$helloPort")
    }

    "forwarding HTTPS ping with a ca signed cert" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp("--https_port", Seq(
        "--disable_http",
        "--ssl_keystore_path", SSLContextTestUtil.caKeyStorePath,
        "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
        "--https_address", "localhost")).futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"

      val forwardPort = forwarder.startForwarder(
        helloPort,
        "--https_port",
        trustStorePath = Some(SSLContextTestUtil.caTrustStorePath),
        args = Seq(
          "--disable_http",
          "--ssl_keystore_path", SSLContextTestUtil.caKeyStorePath,
          "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
          "--https_address", "localhost")).futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"

      val pingURL = new URL(s"https://localhost:$forwardPort/ping")
      val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.caSignedSSLContext)
      val via = connection.getHeaderField(JavaUrlConnectionRequestForwarder.HEADER_VIA)
      val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
      val response = IOUtils.toString(connection.getInputStream, "UTF-8")
      response should be("pong\n")
      via should be(s"1.1 localhost:$forwardPort")
      leader should be(s"https://localhost:$helloPort")
    }

    "direct 404" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp().futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
      val appFacade = new AppMockFacade()
      val result = appFacade.custom("/notfound")("localhost", port = helloPort).futureValue
      result should be(NotFound)
    }

    "forwarding 404" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp().futureValue
      val forwardPort = forwarder.startForwarder(helloPort).futureValue
      val appFacade = new AppMockFacade()
      val result = appFacade.custom("/notfound")("localhost", port = forwardPort).futureValue
      result should be(NotFound)
    }

    "direct internal server error" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp().futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
      val appFacade = new AppMockFacade()
      val result = appFacade.custom("/hello/crash")("localhost", port = helloPort).futureValue
      result should be(ServerError)
      result.entityString should be("Error")
    }

    "forwarding internal server error" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp().futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The hello app did not start in time"
      val forwardPort = forwarder.startForwarder(helloPort).futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"
      val appFacade = new AppMockFacade()
      val result = appFacade.custom("/hello/crash")("localhost", port = forwardPort).futureValue
      result should be(ServerError)
      result.entityString should be("Error")
    }

    "forwarding connection failed" in withForwarder { forwarder =>
      val forwardPort = forwarder.startForwarder(PortAllocator.ephemeralPort()).futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"
      val appFacade = new AppMockFacade()
      val result = appFacade.ping("localhost", port = forwardPort).futureValue
      result should be(BadGateway)
    }

    "forwarding loop" in withForwarder { forwarder =>
      val forwardPort1 = forwarder.startForwarder(PortAllocator.ephemeralPort()).futureValue(forwarderStartTimeout, forwarderStartInterval) withClue "The forwarder service did not start in time"
      forwarder.startForwarder(PortAllocator.ephemeralPort()).futureValue

      val appFacade = new AppMockFacade()
      val result = appFacade.ping("localhost", port = forwardPort1).futureValue
      result should be(BadGateway)
    }

  }
}
