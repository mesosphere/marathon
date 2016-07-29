package mesosphere.marathon.state

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.state.Container.Docker
import org.apache.mesos.Protos
import org.scalatest.{ OptionValues, GivenWhenThen, Matchers, FunSuiteLike }

import scala.collection.immutable.Seq

class AppDefinitionPortAssignmentsTest extends FunSuiteLike with GivenWhenThen with Matchers with OptionValues {
  import MarathonTestHelper.Implicits._

  test("portAssignments with IP-per-task defining ports") {
    Given("An app requesting IP-per-Task and specifying ports in the discovery info")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(
        IpAddress(discoveryInfo = DiscoveryInfo(Seq(DiscoveryInfo.Port(80, "http", "tcp"))))
      )

    Given("A task with an IP address and a port")
    val task = MarathonTestHelper.mininimalTask(app.id)
      .withNetworkInfos(
        Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress("192.168.0.1"))))
      .withHostPorts(Seq(1))

    When("Getting the ports assignments")
    val portAssignments = app.portAssignments(task)

    Then("The right port assignment is returned")
    portAssignments should equal(Some(Seq(
      PortAssignment(portName = Some("http"), effectiveIpAddress = "192.168.0.1", effectivePort = 1)
    )))
  }

  test("portAssignments with IP-per-task defining ports, but a task which doesn't have an IP address yet") {
    Given("An app requesting IP-per-Task and specifying ports in the discovery info")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(
        IpAddress(discoveryInfo = DiscoveryInfo(Seq(DiscoveryInfo.Port(80, "http", "tcp"))))
      )

    Given("A task with no IP address nor host ports")
    val task = MarathonTestHelper.mininimalTask(app.id)
      .withNetworkInfos(Seq.empty)
      .withHostPorts(Seq.empty)

    Then("The port assignments are None")
    app.portAssignments(task) should be(None)
  }

  test("portAssignments with IP-per-task without ports") {
    Given("An app requesting IP-per-Task and not specifying ports in the discovery info")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(
        IpAddress(discoveryInfo = DiscoveryInfo(Seq.empty))
      )

    Given("A task with an IP address and no host ports")
    val task = MarathonTestHelper.mininimalTask(app.id)
      .withNetworkInfos(
        Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress("192.168.0.1"))))
      .withHostPorts(Seq.empty)

    Then("The port assignments are empty")
    app.portAssignments(task).value should be(empty)
  }

  test("portAssignments with a reserved task") {
    Given("An app requesting one port through port definitions")
    val app = MarathonTestHelper.makeBasicApp()

    Given("A reserved task")
    val task = MarathonTestHelper.minimalReservedTask(app.id, MarathonTestHelper.newReservation)

    Then("The port assignments are None")
    app.portAssignments(task) should be(None)
  }

  test("portAssignments without IP-per-task and Docker BRIDGE mode with a port mapping") {
    Given("An app without IP-per-task, using BRIDGE networking with one port mapping requesting a dynamic port")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.BRIDGE)
      .withPortMappings(Seq(
        Docker.PortMapping(containerPort = 80, hostPort = Some(0), servicePort = 0, protocol = "tcp",
          name = Some("http"))
      ))

    Given("A task without an IP and with a host port")
    val task = MarathonTestHelper.mininimalTask(app.id).withHostPorts(Seq(1))

    Then("The right port assignment is returned")
    val portAssignments = app.portAssignments(task)
    portAssignments should be(Some(Seq(
      PortAssignment(portName = Some("http"), effectiveIpAddress = task.agentInfo.host, effectivePort = 1)
    )))
  }

  test("portAssignments without IP-per-task using Docker BRIDGE network and no port mappings") {
    Given("An app using bridge network with no port mappings nor ports")
    val app = MarathonTestHelper.makeBasicApp().copy(
      container = Some(Container.Docker(
        image = "mesosphere/marathon",
        network = Some(Protos.ContainerInfo.DockerInfo.Network.BRIDGE)
      )),
      portDefinitions = Seq.empty
    )

    Given("A task with a port")
    val task = MarathonTestHelper.mininimalTask(app.id)

    Then("The port assignments are empty")
    app.portAssignments(task) should be(None)
  }

  test("portAssignments with IP-per-task using Docker USER networking and a port mapping NOT requesting a host port") {
    Given("An app using IP-per-task, USER networking and with a port mapping requesting no ports")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(IpAddress.empty)
      .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
      .withPortMappings(Seq(
        Docker.PortMapping(containerPort = 80, hostPort = None, servicePort = 0, protocol = "tcp", name = Some("http"))
      ))

    Given("A task with an IP and without a host port")
    val task = MarathonTestHelper.mininimalTask(app.id)
      .withHostPorts(Seq.empty)
      .withNetworkInfos(
        Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress("192.168.0.1")))
      )

    Then("The right port assignment is returned")
    val portAssignments = app.portAssignments(task)
    portAssignments should be(Some(Seq(
      PortAssignment(portName = Some("http"), effectiveIpAddress = "192.168.0.1", effectivePort = 80)
    )))
  }

  test("portAssignments with IP-per-task Docker USER networking and a port mapping requesting a host port") {
    Given("An app using IP-per-task, USER networking and with a port mapping requesting one host port")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(IpAddress.empty)
      .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
      .withPortMappings(Seq(
        Docker.PortMapping(containerPort = 80, hostPort = Some(0), servicePort = 0, protocol = "tcp",
          name = Some("http"))
      ))

    Given("A task with IP-per-task and a host port")
    val task = MarathonTestHelper.mininimalTask(app.id)
      .withHostPorts(Seq(30000))
      .withNetworkInfos(
        Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress("192.168.0.1")))
      )

    Then("The right port assignment is returned")
    val portAssignments = app.portAssignments(task)
    portAssignments should be(Some(Seq(
      PortAssignment(portName = Some("http"), effectiveIpAddress = "192.168.0.1", effectivePort = 80)
    )))
  }

  test("portAssignments with IP-per-task Docker, USER networking, and a mix of port mappings") {
    Given("An app using IP-per-task, USER networking and a mix of port mappings")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(IpAddress.empty)
      .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
      .withPortMappings(Seq(
        Docker.PortMapping(containerPort = 80, hostPort = None, servicePort = 0, protocol = "tcp", name = Some("http")),
        Docker.PortMapping(containerPort = 443, hostPort = Some(0), servicePort = 0, protocol = "tcp",
          name = Some("https"))
      ))

    Given("A task with IP-per-task and a host port")
    val task = MarathonTestHelper.mininimalTask(app.id)
      .withHostPorts(Seq(30000))
      .withNetworkInfos(
        Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress("192.168.0.1")))
      )

    Then("The right port assignment is returned")
    val portAssignments = app.portAssignments(task)
    portAssignments should be(Some(Seq(
      PortAssignment(portName = Some("http"), effectiveIpAddress = "192.168.0.1", effectivePort = 80),
      PortAssignment(portName = Some("https"), effectiveIpAddress = "192.168.0.1", effectivePort = 443)
    )))
  }

  test("portAssignments without IP-per-task, with Docker USER networking and a mix of port mappings") {
    Given("An app not using IP-per-task, with Docker USER networking and a mix of port mappings")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
      .withPortMappings(Seq(
        Docker.PortMapping(containerPort = 80, hostPort = None, servicePort = 0, protocol = "tcp",
          name = Some("http")),
        Docker.PortMapping(containerPort = 443, hostPort = Some(0), servicePort = 0, protocol = "tcp",
          name = Some("https"))
      ))

    Given("A task with a host port")
    val task = MarathonTestHelper.mininimalTask(app.id).withHostPorts(Seq(30000))

    Then("The right port assignment is returned")
    val portAssignments = app.portAssignments(task)
    portAssignments should be(Some(Seq(
      // If there's no IP-per-task and no host port is required, fall back to the container port
      PortAssignment(portName = Some("http"), effectiveIpAddress = task.agentInfo.host, effectivePort = 80),
      // If there's no IP-per-task and a host port is required, use that host port
      PortAssignment(portName = Some("https"), effectiveIpAddress = task.agentInfo.host, effectivePort = 30000)
    )))
  }

  test("portAssignments with port definitions") {
    Given("An app with port definitions")
    val app = MarathonTestHelper.makeBasicApp()
      .withPortDefinitions(Seq(PortDefinition(port = 0, protocol = "tcp", name = Some("http"), labels = Map.empty)))

    Given("A task with one port")
    val task = MarathonTestHelper.mininimalTask(app.id).withHostPorts(Seq(1))

    Then("The right port assignment is returned")
    val portAssignments = app.portAssignments(task)
    portAssignments should be(Some(Seq(
      PortAssignment(portName = Some("http"), effectiveIpAddress = task.agentInfo.host, effectivePort = 1)
    )))
  }

  test("portAssignments with absolutely no ports") {
    import MarathonTestHelper.Implicits._

    Given("An app with absolutely no ports defined")
    val app = MarathonTestHelper.makeBasicApp().withNoPortDefinitions()

    Given("A task with no ports")
    val task = MarathonTestHelper.mininimalTask(app.id).withHostPorts(Seq.empty)

    Then("The port assignments are empty")
    app.portAssignments(task).value should be(empty)
  }
}
