package mesosphere.marathon
package state

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.TestTaskBuilder
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.core.pod.{ BridgeNetwork, ContainerNetwork }
import mesosphere.marathon.state.Container.PortMapping
import mesosphere.marathon.test.MarathonTestHelper

import scala.collection.immutable.Seq

class AppDefinitionPortAssignmentsTest extends UnitTest {
  import MarathonTestHelper.Implicits._

  val hostName = "host.some"

  "AppDefinitionPortAssignment" should {
    "portAssignments with IP-per-task defining ports" in {
      Given("An app requesting IP-per-Task and specifying ports in the discovery info")
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withDockerNetworks(ContainerNetwork("whatever"))
        .withPortMappings(Seq(Container.PortMapping(80, hostPort = Some(0), name = Some("http"), protocol = "tcp")))

      Given("A task with an IP address and a port")
      val task = {
        val t = TestTaskBuilder.Helper.minimalTask(app.id)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts = Seq(1), ipAddresses = Seq(MarathonTestHelper.mesosIpAddress("192.168.0.1")))))
      }

      When("Getting the ports assignments")
      val portAssignments = task.status.networkInfo.portAssignments(app, includeUnresolved = true)

      Then("The right port assignment is returned")
      portAssignments should equal(Seq(
        PortAssignment(
          portName = Some("http"),
          effectiveIpAddress = Some(hostName),
          effectivePort = 1,
          hostPort = Some(1),
          containerPort = Some(80))
      ))
    }

    "portAssignments with IP-per-task defining ports, but a task which doesn't have an IP address yet" in {
      Given("An app requesting IP-per-Task and specifying ports in the discovery info")
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withDockerNetworks(ContainerNetwork("whatever"))
        .withPortMappings(Seq(Container.PortMapping(80, name = Some("http"), protocol = "tcp")))

      Given("A task with no IP address nor host ports")
      val task = {
        val t = TestTaskBuilder.Helper.minimalTask(app.id)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts = Nil, ipAddresses = Nil)))
      }

      Then("The port assignments are empty")
      task.status.networkInfo.portAssignments(app, includeUnresolved = true) should equal(Seq(
        PortAssignment(
          portName = Some("http"),
          effectiveIpAddress = None,
          effectivePort = PortAssignment.NoPort,
          hostPort = None,
          containerPort = Some(80))
      ))
    }

    "portAssignments with IP-per-task without ports" in {
      Given("An app requesting IP-per-Task and not specifying ports in the discovery info")
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withDockerNetworks(ContainerNetwork("whatever")
        )

      Given("A task with an IP address and no host ports")
      val task = {
        val t = TestTaskBuilder.Helper.minimalTask(app.id)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts = Nil, ipAddresses = Seq(MarathonTestHelper.mesosIpAddress("192.168.0.1")))))
      }

      Then("The port assignments are empty")
      task.status.networkInfo.portAssignments(app, includeUnresolved = true) should be(empty)
    }

    "portAssignments with a reserved task" in {
      Given("An app requesting one port through port definitions")
      val app = MarathonTestHelper.makeBasicApp()

      Given("A reserved task")
      val task = TestTaskBuilder.Helper.minimalReservedTask(app.id, TestTaskBuilder.Helper.newReservation)

      Then("The port assignments are empty")
      task.status.networkInfo.portAssignments(app, includeUnresolved = true) should be(empty)
    }

    "portAssignments, without IP-allocation and BRIDGE mode with a port mapping" in {
      Given("An app without IP-per-task, using BRIDGE networking with one port mapping requesting a dynamic port")
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withDockerNetworks(BridgeNetwork())
        .withPortMappings(Seq(
          PortMapping(containerPort = 80, hostPort = Some(0), servicePort = 0, protocol = "tcp",
            name = Some("http"))
        ))

      Given("A task without an IP and with a host port")
      val task = {
        val t = TestTaskBuilder.Helper.minimalTask(app.id)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts = Seq(1), ipAddresses = Nil)))
      }

      Then("The right port assignment is returned")
      val portAssignments = task.status.networkInfo.portAssignments(app, includeUnresolved = true)
      portAssignments should be(Seq(
        PortAssignment(
          portName = Some("http"),
          effectiveIpAddress = Option(hostName),
          effectivePort = 1,
          hostPort = Some(1),
          containerPort = Some(80))
      ))
    }

    "portAssignments without IP-allocation Docker BRIDGE network and no port mappings" in {
      Given("An app using bridge network with no port mappings nor ports")
      val app = MarathonTestHelper.makeBasicApp().copy(
        container = Some(Container.Docker(
          image = "mesosphere/marathon"

        )),
        portDefinitions = Seq.empty,
        networks = Seq(BridgeNetwork()))

      Given("A task with a port")
      val task = TestTaskBuilder.Helper.minimalTask(app.id)

      Then("The port assignments are empty")
      task.status.networkInfo.portAssignments(app, includeUnresolved = true) should be(empty)
    }

    "portAssignments with IP-per-task using Docker USER networking and a port mapping NOT requesting a host port" in {
      Given("An app using IP-per-task, USER networking and with a port mapping requesting no ports")
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withDockerNetworks(ContainerNetwork("whatever"))
        .withPortMappings(Seq(
          PortMapping(containerPort = 80, hostPort = None, servicePort = 0, protocol = "tcp", name = Some("http"))
        ))

      Given("A task with an IP and without a host port")
      val task = {
        val t = TestTaskBuilder.Helper.minimalTask(app.id)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts = Nil, ipAddresses = Seq(MarathonTestHelper.mesosIpAddress("192.168.0.1")))))
      }

      Then("The right port assignment is returned")
      val portAssignments = task.status.networkInfo.portAssignments(app, includeUnresolved = true)
      portAssignments should be(Seq(
        PortAssignment(
          portName = Some("http"),
          effectiveIpAddress = Some("192.168.0.1"),
          effectivePort = 80,
          containerPort = Some(80),
          hostPort = None)
      ))
    }

    "portAssignments with IP-per-task Docker USER networking and a port mapping requesting a host port" in {
      Given("An app using IP-per-task, USER networking and with a port mapping requesting one host port")
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withDockerNetworks(ContainerNetwork("whatever"))
        .withPortMappings(Seq(
          PortMapping(containerPort = 80, hostPort = Some(0), servicePort = 0, protocol = "tcp",
            name = Some("http"))
        ))

      Given("A task with IP-per-task and a host port")
      val task = {
        val t = TestTaskBuilder.Helper.minimalTask(app.id)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts = Seq(30000), ipAddresses = Seq(MarathonTestHelper.mesosIpAddress("192.168.0.1")))))
      }

      Then("The right port assignment is returned")
      val portAssignments = task.status.networkInfo.portAssignments(app, includeUnresolved = true)
      portAssignments should be(Seq(
        PortAssignment(
          portName = Some("http"),
          effectiveIpAddress = Some(hostName),
          effectivePort = 30000,
          containerPort = Some(80),
          hostPort = Some(30000))
      ))
    }

    "portAssignments with IP-per-task Docker, USER networking, and a mix of port mappings" in {
      Given("An app using IP-per-task, USER networking and a mix of port mappings")
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withDockerNetworks(ContainerNetwork("whatever"))
        .withPortMappings(Seq(
          PortMapping(containerPort = 80, hostPort = None, servicePort = 0, protocol = "tcp", name = Some("http")),
          PortMapping(containerPort = 443, hostPort = Some(0), servicePort = 0, protocol = "tcp",
            name = Some("https"))
        ))

      Given("A task with IP-per-task and a host port")
      val task = {
        val t = TestTaskBuilder.Helper.minimalTask(app.id)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts = Seq(30000), ipAddresses = Seq(MarathonTestHelper.mesosIpAddress("192.168.0.1")))))
      }

      Then("The right port assignment is returned")
      val portAssignments = task.status.networkInfo.portAssignments(app, includeUnresolved = true)
      portAssignments should be(Seq(
        PortAssignment(
          portName = Some("http"),
          effectiveIpAddress = Some("192.168.0.1"),
          effectivePort = 80,
          containerPort = Some(80),
          hostPort = None),
        PortAssignment(
          portName = Some("https"),
          effectiveIpAddress = Some(hostName),
          effectivePort = 30000,
          containerPort = Some(443),
          hostPort = Some(30000))
      ))
    }

    "portAssignments with port definitions" in {
      Given("An app with port definitions")
      val app = MarathonTestHelper.makeBasicApp()
        .withPortDefinitions(Seq(PortDefinition(port = 0, protocol = "tcp", name = Some("http"), labels = Map.empty)))

      Given("A task with one port")
      val task = {
        val t = TestTaskBuilder.Helper.minimalTask(app.id)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts = Seq(1), ipAddresses = Nil)))
      }

      Then("The right port assignment is returned")
      val portAssignments = task.status.networkInfo.portAssignments(app, includeUnresolved = true)
      portAssignments should be(Seq(
        PortAssignment(
          portName = Some("http"),
          effectiveIpAddress = task.status.networkInfo.effectiveIpAddress(app),
          effectivePort = 1,
          containerPort = None,
          hostPort = Some(1))
      ))
    }

    "portAssignments with absolutely no ports" in {
      import MarathonTestHelper.Implicits._

      Given("An app with absolutely no ports defined")
      val app = MarathonTestHelper.makeBasicApp().withNoPortDefinitions()

      Given("A task with no ports")
      val task = {
        val t = TestTaskBuilder.Helper.minimalTask(app.id)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts = Nil, ipAddresses = Nil)))
      }

      Then("The port assignments are empty")
      task.status.networkInfo.portAssignments(app, includeUnresolved = true) should be(empty)
    }
  }
}
