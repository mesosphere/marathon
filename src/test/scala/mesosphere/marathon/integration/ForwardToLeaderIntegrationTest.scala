package mesosphere.marathon
package integration

import java.net.URL

import akka.actor.ActorSystem
import mesosphere.IntegrationFunTest
import mesosphere.marathon.api.{ JavaUrlConnectionRequestForwarder, LeaderProxyFilter }
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.io.IO
import mesosphere.util.PortAllocator
import org.apache.commons.httpclient.HttpStatus
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll }

import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
  * Tests forwarding requests.
  */
@IntegrationTest
class ForwardToLeaderIntegrationTest extends IntegrationFunTest with BeforeAndAfter with BeforeAndAfterAll {
  implicit var actorSystem: ActorSystem = _
  val forwarder = new ForwarderService

  before {
    actorSystem = ActorSystem()
  }

  after {
    Await.result(actorSystem.terminate(), Duration.Inf)
    forwarder.close()
  }

  override def afterAll(): Unit = {
    forwarder.close()
    super.afterAll()
  }

  test("direct ping") {
    val helloPort = forwarder.startHelloApp()
    val appFacade = new AppMockFacade()
    val result = appFacade.ping("localhost", port = helloPort)
    assert(result.originalResponse.status.intValue == 200)
    assert(result.entityString == "pong\n")
    assert(!result.originalResponse.headers.exists(_.name == JavaUrlConnectionRequestForwarder.HEADER_VIA))
    assert(result.originalResponse.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) == 1)
    assert(
      result.originalResponse.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value
        == s"http://localhost:$helloPort")
  }

  test("forwarding ping") {
    val helloPort = forwarder.startHelloApp()
    val forwardPort = forwarder.startForwarder(helloPort)

    val appFacade = new AppMockFacade()
    val result = appFacade.ping("localhost", port = forwardPort)
    assert(result.originalResponse.status.intValue == 200)
    assert(result.entityString == "pong\n")
    assert(result.originalResponse.headers.count(_.name == JavaUrlConnectionRequestForwarder.HEADER_VIA) == 1)
    assert(
      result.originalResponse.headers.find(_.name == JavaUrlConnectionRequestForwarder.HEADER_VIA).get.value
        == s"1.1 localhost:$forwardPort")
    assert(result.originalResponse.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) == 1)
    assert(
      result.originalResponse.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value
        == s"http://localhost:$helloPort")
  }

  test("direct HTTPS ping") {
    val helloPort = forwarder.startHelloApp("--https_port", Seq(
      "--disable_http",
      "--ssl_keystore_path", SSLContextTestUtil.selfSignedKeyStorePath,
      "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
      "--https_address", "localhost"))

    val pingURL = new URL(s"https://localhost:$helloPort/ping")
    val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.selfSignedSSLContext)
    val via = connection.getHeaderField(JavaUrlConnectionRequestForwarder.HEADER_VIA)
    val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
    val response = IO.using(connection.getInputStream)(IO.copyInputStreamToString)
    assert(response == "pong\n")
    assert(via == null)
    assert(leader == s"https://localhost:$helloPort")
  }

  test("forwarding HTTPS ping with a self-signed cert") {
    val helloPort = forwarder.startHelloApp("--https_port", Seq(
      "--disable_http",
      "--ssl_keystore_path", SSLContextTestUtil.selfSignedKeyStorePath,
      "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
      "--https_address", "localhost"))

    val forwardPort = forwarder.startForwarder(helloPort, "--https_port", args = Seq(
      "--disable_http",
      "--ssl_keystore_path", SSLContextTestUtil.selfSignedKeyStorePath,
      "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
      "--https_address", "localhost"))

    val pingURL = new URL(s"https://localhost:$forwardPort/ping")
    val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.selfSignedSSLContext)
    val via = connection.getHeaderField(JavaUrlConnectionRequestForwarder.HEADER_VIA)
    val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
    val response = IO.using(connection.getInputStream)(IO.copyInputStreamToString)
    assert(response == "pong\n")
    assert(via == s"1.1 localhost:$forwardPort")
    assert(leader == s"https://localhost:$helloPort")
  }

  test("forwarding HTTPS ping with a ca signed cert") {
    val helloPort = forwarder.startHelloApp("--https_port", Seq(
      "--disable_http",
      "--ssl_keystore_path", SSLContextTestUtil.caKeyStorePath,
      "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
      "--https_address", "localhost"))

    val forwardPort = forwarder.startForwarder(
      helloPort,
      "--https_port",
      trustStorePath = Some(SSLContextTestUtil.caTrustStorePath),
      args = Seq(
        "--disable_http",
        "--ssl_keystore_path", SSLContextTestUtil.caKeyStorePath,
        "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
        "--https_address", "localhost"))

    val pingURL = new URL(s"https://localhost:$forwardPort/ping")
    val connection = SSLContextTestUtil.sslConnection(pingURL, SSLContextTestUtil.caSignedSSLContext)
    val via = connection.getHeaderField(JavaUrlConnectionRequestForwarder.HEADER_VIA)
    val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
    val response = IO.using(connection.getInputStream)(IO.copyInputStreamToString)
    assert(response == "pong\n")
    assert(via == s"1.1 localhost:$forwardPort")
    assert(leader == s"https://localhost:$helloPort")
  }

  test("direct 404") {
    val helloPort = forwarder.startHelloApp()
    val appFacade = new AppMockFacade()
    val result = appFacade.custom("/notfound")("localhost", port = helloPort)
    assert(result.originalResponse.status.intValue == 404)
  }

  test("forwarding 404") {
    val helloPort = forwarder.startHelloApp()
    val forwardPort = forwarder.startForwarder(helloPort)
    val appFacade = new AppMockFacade()
    val result = appFacade.custom("/notfound")("localhost", port = forwardPort)
    assert(result.originalResponse.status.intValue == 404)
  }

  test("direct internal server error") {
    val helloPort = forwarder.startHelloApp()
    val appFacade = new AppMockFacade()
    val result = appFacade.custom("/hello/crash")("localhost", port = helloPort)
    assert(result.originalResponse.status.intValue == 500)
    assert(result.entityString == "Error")
  }

  test("forwarding internal server error") {
    val helloPort = forwarder.startHelloApp()
    val forwardPort = forwarder.startForwarder(helloPort)
    val appFacade = new AppMockFacade()
    val result = appFacade.custom("/hello/crash")("localhost", port = forwardPort)
    assert(result.originalResponse.status.intValue == 500)
    assert(result.entityString == "Error")
  }

  test("forwarding connection failed") {
    val forwardPort = forwarder.startForwarder(PortAllocator.ephemeralPort())
    val appFacade = new AppMockFacade()
    val result = appFacade.ping("localhost", port = forwardPort)
    assert(result.originalResponse.status.intValue == HttpStatus.SC_BAD_GATEWAY)
  }

  test("forwarding loop") {
    val forwardPort1 = forwarder.startForwarder(PortAllocator.ephemeralPort())
    val forwardPort2 = forwarder.startForwarder(PortAllocator.ephemeralPort())

    val appFacade = new AppMockFacade()
    val result = appFacade.ping("localhost", port = forwardPort1)
    assert(result.originalResponse.status.intValue == HttpStatus.SC_BAD_GATEWAY)
  }

}
