package mesosphere.marathon
package state

import mesosphere.marathon.core.instance.TestTaskBuilder
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.Container.PortMapping
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.Protos
import org.scalatest.{ FunSuiteLike, GivenWhenThen, Matchers, OptionValues }

import scala.collection.immutable.Seq

class AppDefinitionPortAssignmentsTest extends FunSuiteLike with GivenWhenThen with Matchers with OptionValues {
  import MarathonTestHelper.Implicits._

  val hostName = "host.some"

  test("portAssignments with IP-per-task defining ports") {
    Given("An app requesting IP-per-Task and specifying ports in the discovery info")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(
        IpAddress(discoveryInfo = DiscoveryInfo(Seq(DiscoveryInfo.Port(80, "http", "tcp"))))
      )

    Given("A task with an IP address and a port")
    val task = {
      val t = TestTaskBuilder.Helper.minimalTask(app.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts = Seq(1), ipAddresses = Some(Seq(MarathonTestHelper.mesosIpAddress("192.168.0.1"))))))
    }

    When("Getting the ports assignments")
    val portAssignments = task.status.networkInfo.portAssignments(app)

    Then("The right port assignment is returned")
    portAssignments should equal(Seq(
      PortAssignment(
        portName = Some("http"),
        effectiveIpAddress = Some("192.168.0.1"),
        effectivePort = 1,
        hostPort = Some(1),
        containerPort = None)
    ))
  }

  test("portAssignments with IP-per-task defining ports, but a task which doesn't have an IP address yet") {
    Given("An app requesting IP-per-Task and specifying ports in the discovery info")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(
        IpAddress(discoveryInfo = DiscoveryInfo(Seq(DiscoveryInfo.Port(80, "http", "tcp"))))
      )

    Given("A task with no IP address nor host ports")
    val task = {
      val t = TestTaskBuilder.Helper.minimalTask(app.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts = Nil, ipAddresses = Some(Nil))))
    }

    Then("The port assignments are empty")
    task.status.networkInfo.portAssignments(app) should be(empty)
  }

  test("portAssignments with IP-per-task without ports") {
    Given("An app requesting IP-per-Task and not specifying ports in the discovery info")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(
        IpAddress(discoveryInfo = DiscoveryInfo(Seq.empty))
      )

    Given("A task with an IP address and no host ports")
    val task = {
      val t = TestTaskBuilder.Helper.minimalTask(app.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts = Nil, ipAddresses = Some(Seq(MarathonTestHelper.mesosIpAddress("192.168.0.1"))))))
    }

    Then("The port assignments are empty")
    task.status.networkInfo.portAssignments(app) should be(empty)
  }

  test("portAssignments with a reserved task") {
    Given("An app requesting one port through port definitions")
    val app = MarathonTestHelper.makeBasicApp()

    Given("A reserved task")
    val task = TestTaskBuilder.Helper.minimalReservedTask(app.id, TestTaskBuilder.Helper.newReservation)

    Then("The port assignments are empty")
    task.status.networkInfo.portAssignments(app) should be(empty)
  }

  test("portAssignments without IP-per-task and Docker BRIDGE mode with a port mapping") {
    Given("An app without IP-per-task, using BRIDGE networking with one port mapping requesting a dynamic port")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.BRIDGE)
      .withPortMappings(Seq(
        PortMapping(containerPort = 80, hostPort = Some(0), servicePort = 0, protocol = "tcp",
          name = Some("http"))
      ))

    Given("A task without an IP and with a host port")
    val task = {
      val t = TestTaskBuilder.Helper.minimalTask(app.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts = Seq(1), ipAddresses = None)))
    }

    Then("The right port assignment is returned")
    val portAssignments = task.status.networkInfo.portAssignments(app)
    portAssignments should be(Seq(
      PortAssignment(
        portName = Some("http"),
        effectiveIpAddress = Some(task.agentInfo.host),
        effectivePort = 1,
        hostPort = Some(1),
        containerPort = Some(80))
    ))
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
    val task = TestTaskBuilder.Helper.minimalTask(app.id)

    Then("The port assignments are empty")
    task.status.networkInfo.portAssignments(app) should be(empty)
  }

  test("portAssignments with IP-per-task using Docker USER networking and a port mapping NOT requesting a host port") {
    Given("An app using IP-per-task, USER networking and with a port mapping requesting no ports")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(IpAddress.empty)
      .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
      .withPortMappings(Seq(
        PortMapping(containerPort = 80, hostPort = None, servicePort = 0, protocol = "tcp", name = Some("http"))
      ))

    Given("A task with an IP and without a host port")
    val task = {
      val t = TestTaskBuilder.Helper.minimalTask(app.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts = Nil, ipAddresses = Some(Seq(MarathonTestHelper.mesosIpAddress("192.168.0.1"))))))
    }

    Then("The right port assignment is returned")
    val portAssignments = task.status.networkInfo.portAssignments(app)
    portAssignments should be(Seq(
      PortAssignment(
        portName = Some("http"),
        effectiveIpAddress = Some("192.168.0.1"),
        effectivePort = 80,
        containerPort = Some(80),
        hostPort = None)
    ))
  }

  test("portAssignments with IP-per-task Docker USER networking and a port mapping requesting a host port") {
    Given("An app using IP-per-task, USER networking and with a port mapping requesting one host port")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(IpAddress.empty)
      .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
      .withPortMappings(Seq(
        PortMapping(containerPort = 80, hostPort = Some(0), servicePort = 0, protocol = "tcp",
          name = Some("http"))
      ))

    Given("A task with IP-per-task and a host port")
    val task = {
      val t = TestTaskBuilder.Helper.minimalTask(app.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts = Seq(30000), ipAddresses = Some(Seq(MarathonTestHelper.mesosIpAddress("192.168.0.1"))))))
    }

    Then("The right port assignment is returned")
    val portAssignments = task.status.networkInfo.portAssignments(app)
    portAssignments should be(Seq(
      PortAssignment(
        portName = Some("http"),
        effectiveIpAddress = Some("192.168.0.1"),
        effectivePort = 80,
        containerPort = Some(80),
        hostPort = Some(30000))
    ))
  }

  test("portAssignments with IP-per-task Docker, USER networking, and a mix of port mappings") {
    Given("An app using IP-per-task, USER networking and a mix of port mappings")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(IpAddress.empty)
      .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
      .withPortMappings(Seq(
        PortMapping(containerPort = 80, hostPort = None, servicePort = 0, protocol = "tcp", name = Some("http")),
        PortMapping(containerPort = 443, hostPort = Some(0), servicePort = 0, protocol = "tcp",
          name = Some("https"))
      ))

    Given("A task with IP-per-task and a host port")
    val task = {
      val t = TestTaskBuilder.Helper.minimalTask(app.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts = Seq(30000), ipAddresses = Some(Seq(MarathonTestHelper.mesosIpAddress("192.168.0.1"))))))
    }

    Then("The right port assignment is returned")
    val portAssignments = task.status.networkInfo.portAssignments(app)
    portAssignments should be(Seq(
      PortAssignment(
        portName = Some("http"),
        effectiveIpAddress = Some("192.168.0.1"),
        effectivePort = 80,
        containerPort = Some(80),
        hostPort = None),
      PortAssignment(
        portName = Some("https"),
        effectiveIpAddress = Some("192.168.0.1"),
        effectivePort = 443,
        containerPort = Some(443),
        hostPort = Some(30000))
    ))
  }

  test("portAssignments without IP-per-task, with Docker USER networking and a mix of port mappings") {
    Given("An app not using IP-per-task, with Docker USER networking and a mix of port mappings")
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
      .withPortMappings(Seq(
        PortMapping(containerPort = 80, hostPort = None, servicePort = 0, protocol = "tcp",
          name = Some("http")),
        PortMapping(containerPort = 443, hostPort = Some(0), servicePort = 0, protocol = "tcp",
          name = Some("https"))
      ))

    Given("A task with a host port")
    val task = {
      val t = TestTaskBuilder.Helper.minimalTask(app.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts = Seq(30000), ipAddresses = None)))
    }

    Then("The right port assignment is returned")
    val portAssignments = task.status.networkInfo.portAssignments(app)
    portAssignments should be(Seq(
      // If there's no IP-per-task and no host port is required, fall back to the container port
      PortAssignment(
        portName = Some("http"),
        effectiveIpAddress = Some(task.agentInfo.host),
        effectivePort = 80,
        containerPort = Some(80),
        hostPort = None),
      // If there's no IP-per-task and a host port is required, use that host port
      PortAssignment(
        portName = Some("https"),
        effectiveIpAddress = Some(task.agentInfo.host),
        effectivePort = 30000,
        containerPort = Some(443),
        hostPort = Some(30000))
    ))
  }

  test("portAssignments with port definitions") {
    Given("An app with port definitions")
    val app = MarathonTestHelper.makeBasicApp()
      .withPortDefinitions(Seq(PortDefinition(port = 0, protocol = "tcp", name = Some("http"), labels = Map.empty)))

    Given("A task with one port")
    val task = {
      val t = TestTaskBuilder.Helper.minimalTask(app.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts = Seq(1), ipAddresses = None)))
    }

    Then("The right port assignment is returned")
    val portAssignments = task.status.networkInfo.portAssignments(app)
    portAssignments should be(Seq(
      PortAssignment(
        portName = Some("http"),
        effectiveIpAddress = Some(task.agentInfo.host),
        effectivePort = 1,
        containerPort = None,
        hostPort = Some(1))
    ))
  }

  test("portAssignments with absolutely no ports") {
    import MarathonTestHelper.Implicits._

    Given("An app with absolutely no ports defined")
    val app = MarathonTestHelper.makeBasicApp().withNoPortDefinitions()

    Given("A task with no ports")
    val task = {
      val t = TestTaskBuilder.Helper.minimalTask(app.id)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts = Nil, ipAddresses = None)))
    }

    Then("The port assignments are empty")
    task.status.networkInfo.portAssignments(app) should be(empty)
  }
}
