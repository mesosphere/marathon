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
    portAssignments should equal(
      Some(
        Seq(
          PortAssignment(
            portName = Some("http"),
            portIndex = 0,
            effectiveIpAddress = "192.168.0.1",
            effectivePort = 1)
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

  test("portAssignments with port mappings") {
    Given("An app requesting one port through port definitions")
    val app = MarathonTestHelper.makeBasicApp().copy(
      container = Some(Container(
        docker = Some(Docker(
          image = "mesosphere/marathon",
          network = Some(Protos.ContainerInfo.DockerInfo.Network.BRIDGE),
          portMappings = Some(Seq(
            Docker.PortMapping(containerPort = 80, hostPort = 0, servicePort = 0, protocol = "tcp",
              name = Some("http"))
          ))
        ))
      )),
      portDefinitions = Seq.empty)

    Given("A task with a host port")
    val task = MarathonTestHelper.mininimalTask(app.id).withHostPorts(Seq(1))

    Then("The right port assignment is returned")
    val portAssignments = app.portAssignments(task)
    portAssignments should be(
      Some(
        Seq(
          PortAssignment(
            portName = Some("http"),
            portIndex = 0,
            effectiveIpAddress = task.agentInfo.host,
            effectivePort = 1)
        )))
  }

  test("portAssignments with bridge network and no port mappings") {
    Given("An app using bridge network with no port mappings nor ports")
    val app = MarathonTestHelper.makeBasicApp().copy(
      container = Some(Container(
        docker = Some(Docker(
          image = "mesosphere/marathon",
          network = Some(Protos.ContainerInfo.DockerInfo.Network.BRIDGE),
          portMappings = Some(Seq.empty))))
      ),
      portDefinitions = Seq.empty)

    Given("A task with a port")
    val task = MarathonTestHelper.mininimalTask(app.id)

    Then("The port assignments are empty")
    app.portAssignments(task).value should be(empty)
  }

  test("portAssignments with port definitions") {
    Given("An app with port definitions")
    val app = MarathonTestHelper.makeBasicApp()
      .withPortDefinitions(Seq(PortDefinition(port = 0, protocol = "tcp", name = Some("http"), labels = Map.empty)))

    Given("A task with one port")
    val task = MarathonTestHelper.mininimalTask(app.id).withHostPorts(Seq(1))

    Then("The right port assignment is returned")
    val portAssignments = app.portAssignments(task)
    portAssignments should be(
      Some(
        Seq(
          PortAssignment(
            portName = Some("http"),
            portIndex = 0,
            effectiveIpAddress = task.agentInfo.host,
            effectivePort = 1)
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
