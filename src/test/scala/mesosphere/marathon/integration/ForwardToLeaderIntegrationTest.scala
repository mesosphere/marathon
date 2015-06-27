package mesosphere.marathon.integration

import java.net.URL
import javax.net.ssl.HttpsURLConnection

import akka.actor.ActorSystem
import mesosphere.marathon.api.{ LeaderProxyFilter, JavaUrlConnectionRequestForwarder }
import mesosphere.marathon.integration.setup._
import org.apache.commons.httpclient.HttpStatus
import org.scalatest.BeforeAndAfter
import mesosphere.marathon.io.IO

/**
  * Tests forwarding requests.
  */
class ForwardToLeaderIntegrationTest extends IntegrationFunSuite with BeforeAndAfter {
  // ports to bind to
  private[this] val ports = 10000 to 20000

  implicit var actorSystem: ActorSystem = _

  before {
    actorSystem = ActorSystem()
  }

  after {
    actorSystem.shutdown()
    actorSystem.awaitTermination()
    ProcessKeeper.shutdown()
  }

  test("direct ping") {
    ProcessKeeper.startService(ForwarderService.createHelloApp("--http_port", ports.head.toString))
    val appFacade = new AppMockFacade()
    val result = appFacade.ping("localhost", port = ports.head)
    assert(result.originalResponse.status.intValue == 200)
    assert(result.entityString == "pong\n")
    assert(!result.originalResponse.headers.exists(_.name == JavaUrlConnectionRequestForwarder.HEADER_VIA))
    assert(result.originalResponse.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) == 1)
    assert(
      result.originalResponse.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value
        == s"http://localhost:${ports.head}")
  }

  test("forwarding ping") {
    // We cannot start two service in one process because of static variables in GuiceFilter
    ForwarderService.startHelloAppProcess("--http_port", ports.head.toString)
    ProcessKeeper.startService(ForwarderService.createForwarder(
      forwardToPort = ports.head, "--http_port", ports(1).toString))
    val appFacade = new AppMockFacade()
    val result = appFacade.ping("localhost", port = ports(1))
    assert(result.originalResponse.status.intValue == 200)
    assert(result.entityString == "pong\n")
    assert(result.originalResponse.headers.count(_.name == JavaUrlConnectionRequestForwarder.HEADER_VIA) == 1)
    assert(
      result.originalResponse.headers.find(_.name == JavaUrlConnectionRequestForwarder.HEADER_VIA).get.value
        == s"1.1 localhost:${ports(1)}")
    assert(result.originalResponse.headers.count(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER) == 1)
    assert(
      result.originalResponse.headers.find(_.name == LeaderProxyFilter.HEADER_MARATHON_LEADER).get.value
        == s"http://localhost:${ports.head}")
  }

  test("direct HTTPS ping") {
    ProcessKeeper.startService(ForwarderService.createHelloApp(
      "--disable_http",
      "--ssl_keystore_path", SSLContextTestUtil.keyStorePath,
      "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
      "--https_address", "localhost",
      "--https_port", ports.head.toString))

    val pingURL = new URL(s"https://localhost:${ports.head}/ping")
    val connection = SSLContextTestUtil.sslConnection(pingURL)
    val via = connection.getHeaderField(JavaUrlConnectionRequestForwarder.HEADER_VIA)
    val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
    val response = IO.using(connection.getInputStream)(IO.copyInputStreamToString)
    assert(response == "pong\n")
    assert(via == null)
    assert(leader == s"https://localhost:${ports.head}")
  }

  test("forwarding HTTPS ping") {
    // We cannot start two service in one process because of static variables in GuiceFilter
    ProcessKeeper.startService(ForwarderService.createHelloApp(
      "--disable_http",
      "--ssl_keystore_path", SSLContextTestUtil.keyStorePath,
      "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
      "--https_address", "localhost",
      "--https_port", ports.head.toString))

    ForwarderService.startForwarderProcess(
      forwardToPort = ports.head,
      "--disable_http",
      "--ssl_keystore_path", SSLContextTestUtil.keyStorePath,
      "--ssl_keystore_password", SSLContextTestUtil.keyStorePassword,
      "--https_address", "localhost",
      "--https_port", ports(1).toString)

    val pingURL = new URL(s"https://localhost:${ports(1)}/ping")
    val connection = SSLContextTestUtil.sslConnection(pingURL)
    val via = connection.getHeaderField(JavaUrlConnectionRequestForwarder.HEADER_VIA)
    val leader = connection.getHeaderField(LeaderProxyFilter.HEADER_MARATHON_LEADER)
    val response = IO.using(connection.getInputStream)(IO.copyInputStreamToString)
    assert(response == "pong\n")
    assert(via == s"1.1 localhost:${ports(1)}")
    assert(leader == s"https://localhost:${ports.head}")
  }

  test("direct 404") {
    ProcessKeeper.startService(ForwarderService.createHelloApp("--http_port", ports.head.toString))
    val appFacade = new AppMockFacade()
    val result = appFacade.custom("/notfound")("localhost", port = ports.head)
    assert(result.originalResponse.status.intValue == 404)
  }

  test("forwarding 404") {
    // We cannot start two service in one process because of static variables in GuiceFilter
    ForwarderService.startHelloAppProcess("--http_port", ports.head.toString)
    ProcessKeeper.startService(ForwarderService.createForwarder(forwardToPort = ports.head, "--http_port",
      ports(1).toString))
    val appFacade = new AppMockFacade()
    val result = appFacade.custom("/notfound")("localhost", port = ports(1))
    assert(result.originalResponse.status.intValue == 404)
  }

  test("direct internal server error") {
    ProcessKeeper.startService(ForwarderService.createHelloApp("--http_port", ports.head.toString))
    val appFacade = new AppMockFacade()
    val result = appFacade.custom("/hello/crash")("localhost", port = ports.head)
    assert(result.originalResponse.status.intValue == 500)
    assert(result.entityString == "Error")
  }

  test("forwarding internal server error") {
    // We cannot start two service in one process because of static variables in GuiceFilter
    ForwarderService.startHelloAppProcess("--http_port", ports.head.toString)
    ProcessKeeper.startService(ForwarderService.createForwarder(forwardToPort = ports.head, "--http_port",
      ports(1).toString))
    val appFacade = new AppMockFacade()
    val result = appFacade.custom("/hello/crash")("localhost", port = ports(1))
    assert(result.originalResponse.status.intValue == 500)
    assert(result.entityString == "Error")
  }

  test("forwarding connection failed") {
    ProcessKeeper.startService(ForwarderService.createForwarder(
      forwardToPort = ports.head, "--http_port", ports(1).toString))
    val appFacade = new AppMockFacade()
    val result = appFacade.ping("localhost", port = ports(1))
    assert(result.originalResponse.status.intValue == HttpStatus.SC_BAD_GATEWAY)
  }

  test("forwarding loop") {
    // We cannot start two service in one process because of static variables in GuiceFilter
    ForwarderService.startForwarderProcess(
      forwardToPort = ports(1), "--http_port", ports.head.toString)
    ProcessKeeper.startService(ForwarderService.createForwarder(
      forwardToPort = ports.head, "--http_port", ports(1).toString))
    val appFacade = new AppMockFacade()
    val result = appFacade.ping("localhost", port = ports(1))
    assert(result.originalResponse.status.intValue == HttpStatus.SC_BAD_GATEWAY)
  }

}
