package mesosphere.marathon.integration

import akka.actor.ActorSystem
import mesosphere.marathon.integration.setup.{ AppMockFacade, ForwarderService, IntegrationFunSuite, ProcessKeeper }
import org.apache.commons.httpclient.HttpStatus
import org.scalatest.BeforeAndAfter

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
    ProcessKeeper.startService(ForwarderService.createHelloApp(ports.head))
    val appFacade = new AppMockFacade()
    val result = appFacade.ping("localhost", port = ports.head)
    assert(result.originalResponse.status.intValue == 200)
    assert(result.entityString == "pong\n")
  }

  test("forwarding ping") {
    // We cannot start two service in one process because of static variables in GuiceFilter
    ForwarderService.startHelloAppProcess(ports.head)
    ProcessKeeper.startService(ForwarderService.createForwarder(port = ports(1), forwardToPort = ports.head))
    val appFacade = new AppMockFacade()
    val result = appFacade.ping("localhost", port = ports(1))
    assert(result.originalResponse.status.intValue == 200)
    assert(result.entityString == "pong\n")
  }

  test("direct 404") {
    ProcessKeeper.startService(ForwarderService.createHelloApp(ports.head))
    val appFacade = new AppMockFacade()
    val result = appFacade.custom("/notfound")("localhost", port = ports.head)
    assert(result.originalResponse.status.intValue == 404)
  }

  test("forwarding 404") {
    // We cannot start two service in one process because of static variables in GuiceFilter
    ForwarderService.startHelloAppProcess(ports.head)
    ProcessKeeper.startService(ForwarderService.createForwarder(port = ports(1), forwardToPort = ports.head))
    val appFacade = new AppMockFacade()
    val result = appFacade.custom("/notfound")("localhost", port = ports(1))
    assert(result.originalResponse.status.intValue == 404)
  }

  test("direct internal server error") {
    ProcessKeeper.startService(ForwarderService.createHelloApp(ports.head))
    val appFacade = new AppMockFacade()
    val result = appFacade.custom("/hello/crash")("localhost", port = ports.head)
    assert(result.originalResponse.status.intValue == 500)
    assert(result.entityString == "Error")
  }

  test("forwarding internal server error") {
    // We cannot start two service in one process because of static variables in GuiceFilter
    ForwarderService.startHelloAppProcess(ports.head)
    ProcessKeeper.startService(ForwarderService.createForwarder(port = ports(1), forwardToPort = ports.head))
    val appFacade = new AppMockFacade()
    val result = appFacade.custom("/hello/crash")("localhost", port = ports(1))
    assert(result.originalResponse.status.intValue == 500)
    assert(result.entityString == "Error")
  }

  test("forwarding connection failed") {
    ProcessKeeper.startService(ForwarderService.createForwarder(port = ports(1), forwardToPort = ports.head))
    val appFacade = new AppMockFacade()
    val result = appFacade.ping("localhost", port = ports(1))
    assert(result.originalResponse.status.intValue == HttpStatus.SC_BAD_GATEWAY)
  }

  test("forwarding loop") {
    // We cannot start two service in one process because of static variables in GuiceFilter
    ForwarderService.startForwarderProcess(ports.head, forwardToPort = ports(1))
    ProcessKeeper.startService(ForwarderService.createForwarder(port = ports(1), forwardToPort = ports.head))
    val appFacade = new AppMockFacade()
    val result = appFacade.ping("localhost", port = ports(1))
    assert(result.originalResponse.status.intValue == HttpStatus.SC_BAD_GATEWAY)
  }

}
