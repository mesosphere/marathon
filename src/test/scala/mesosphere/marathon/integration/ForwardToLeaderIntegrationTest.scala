package mesosphere.marathon
package integration

import java.net.URL

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.api.{ JavaUrlConnectionRequestForwarder, LeaderProxyFilter }
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.io.IO
import mesosphere.util.PortAllocator
import akka.http.scaladsl.model.StatusCodes._
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.concurrent.duration._

/**
  * Tests forwarding requests.
  */
@IntegrationTest
@SerialIntegrationTest
class ForwardToLeaderIntegrationTest extends AkkaIntegrationTest {
  def withForwarder[T](testCode: ForwarderService => T): T = {
    val forwarder = new ForwarderService
    try {
      testCode(forwarder)
    } finally {
      forwarder.close()
    }
  }

  "ForwardingToLeader" should {
    "direct ping" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp().futureValue(Timeout(30.seconds))
      val appFacade = new AppMockFacade()
      val result = appFacade.ping("localhost", port = helloPort).futureValue
      assert(result.response.status.intValue == 200)
      assert(result.asString == "pong\n")
      assert(!result.response.headers.exists(_.name == JavaUrlConnectionRequestForwarder.HEADER_VIA))
      assert(result.response.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) == 1)
      assert(
        result.response.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value
          == s"http://localhost:$helloPort")
    }

    "forwarding ping" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp().futureValue(Timeout(30.seconds))
      val forwardPort = forwarder.startForwarder(helloPort).futureValue(Timeout(30.seconds))

      val appFacade = new AppMockFacade()
      val result = appFacade.ping("localhost", port = forwardPort).futureValue
      assert(result.response.status.intValue == 200)
      assert(result.asString == "pong\n")
      assert(result.response.headers.count(_.name == JavaUrlConnectionRequestForwarder.HEADER_VIA) == 1)
      assert(
        result.response.headers.find(_.name == JavaUrlConnectionRequestForwarder.HEADER_VIA).get.value
          == s"1.1 localhost:$forwardPort")
      assert(result.response.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) == 1)
      assert(
        result.response.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value
          == s"http://localhost:$helloPort")
    }

    "direct HTTPS ping" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp("--https_port", Seq(
        "--disable_http",
        "--ssl_keystore_path", SSLContextTestUtil.selfSignedKeyStorePath,
        "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
        "--https_address", "localhost")).futureValue(Timeout(30.seconds))

      val pingURL = new URL(s"https://localhost:$helloPort/ping")
      val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.selfSignedSSLContext)
      val via = connection.getHeaderField(JavaUrlConnectionRequestForwarder.HEADER_VIA)
      val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
      val response = IO.using(connection.getInputStream)(IO.copyInputStreamToString)
      assert(response == "pong\n")
      assert(via == null)
      assert(leader == s"https://localhost:$helloPort")
    }

    "forwarding HTTPS ping with a self-signed cert" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp("--https_port", Seq(
        "--disable_http",
        "--ssl_keystore_path", SSLContextTestUtil.selfSignedKeyStorePath,
        "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
        "--https_address", "localhost")).futureValue(Timeout(30.seconds))

      val forwardPort = forwarder.startForwarder(helloPort, "--https_port", args = Seq(
        "--disable_http",
        "--ssl_keystore_path", SSLContextTestUtil.selfSignedKeyStorePath,
        "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
        "--https_address", "localhost")).futureValue(Timeout(30.seconds))

      val pingURL = new URL(s"https://localhost:$forwardPort/ping")
      val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.selfSignedSSLContext)
      val via = connection.getHeaderField(JavaUrlConnectionRequestForwarder.HEADER_VIA)
      val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
      val response = IO.using(connection.getInputStream)(IO.copyInputStreamToString)
      assert(response == "pong\n")
      assert(via == s"1.1 localhost:$forwardPort")
      assert(leader == s"https://localhost:$helloPort")
    }

    "forwarding HTTPS ping with a ca signed cert" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp("--https_port", Seq(
        "--disable_http",
        "--ssl_keystore_path", SSLContextTestUtil.caKeyStorePath,
        "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
        "--https_address", "localhost")).futureValue(Timeout(30.seconds))

      val forwardPort = forwarder.startForwarder(
        helloPort,
        "--https_port",
        trustStorePath = Some(SSLContextTestUtil.caTrustStorePath),
        args = Seq(
          "--disable_http",
          "--ssl_keystore_path", SSLContextTestUtil.caKeyStorePath,
          "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
          "--https_address", "localhost")).futureValue(Timeout(30.seconds))

      val pingURL = new URL(s"https://localhost:$forwardPort/ping")
      val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.caSignedSSLContext)
      val via = connection.getHeaderField(JavaUrlConnectionRequestForwarder.HEADER_VIA)
      val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
      val response = IO.using(connection.getInputStream)(IO.copyInputStreamToString)
      assert(response == "pong\n")
      assert(via == s"1.1 localhost:$forwardPort")
      assert(leader == s"https://localhost:$helloPort")
    }

    "direct 404" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp().futureValue(Timeout(30.seconds))
      val appFacade = new AppMockFacade()
      val result = appFacade.custom("/notfound")("localhost", port = helloPort).futureValue
      assert(result.response.status.intValue == 404)
    }

    "forwarding 404" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp().futureValue(Timeout(30.seconds))
      val forwardPort = forwarder.startForwarder(helloPort).futureValue(Timeout(30.seconds))
      val appFacade = new AppMockFacade()
      val result = appFacade.custom("/notfound")("localhost", port = forwardPort).futureValue
      assert(result.response.status.intValue == 404)
    }

    "direct internal server error" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp().futureValue(Timeout(30.seconds))
      val appFacade = new AppMockFacade()
      val result = appFacade.custom("/hello/crash")("localhost", port = helloPort).futureValue
      assert(result.response.status.intValue == 500)
      assert(result.asString == "Error")
    }

    "forwarding internal server error" in withForwarder { forwarder =>
      val helloPort = forwarder.startHelloApp().futureValue(Timeout(30.seconds))
      val forwardPort = forwarder.startForwarder(helloPort).futureValue(Timeout(30.seconds))
      val appFacade = new AppMockFacade()
      val result = appFacade.custom("/hello/crash")("localhost", port = forwardPort).futureValue
      assert(result.response.status.intValue == 500)
      assert(result.asString == "Error")
    }

    "forwarding connection failed" in withForwarder { forwarder =>
      val forwardPort = forwarder.startForwarder(PortAllocator.ephemeralPort()).futureValue(Timeout(30.seconds))
      val appFacade = new AppMockFacade()
      val result = appFacade.ping("localhost", port = forwardPort).futureValue
      assert(result.response.status.intValue == BadGateway.intValue)
    }

    "forwarding loop" in withForwarder { forwarder =>
      val forwardPort1 = forwarder.startForwarder(PortAllocator.ephemeralPort()).futureValue(Timeout(30.seconds))
      forwarder.startForwarder(PortAllocator.ephemeralPort()).futureValue(Timeout(30.seconds))

      val appFacade = new AppMockFacade()
      val result = appFacade.ping("localhost", port = forwardPort1).futureValue
      assert(result.response.status.intValue == BadGateway.intValue)
    }

  }
}
