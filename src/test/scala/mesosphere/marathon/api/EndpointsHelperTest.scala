package mesosphere.marathon
package api

import mesosphere.UnitTest
import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.{ IpAddress, PortDefinition }
import org.apache.mesos.Protos
import mesosphere.marathon.state.Container.Docker

import scala.collection.immutable.Seq

class EndpointsHelperTest extends UnitTest {
  import MarathonTestHelper.Implicits._

  "rendering docker containers with ip-per-container" should {

    "routput the container port and container IP when hostPort is empty" in {
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withIpAddress(IpAddress.empty)
        .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
        .withPortMappings(Seq(
          Docker.PortMapping(containerPort = 80, hostPort = None, servicePort = 10000, protocol = "tcp", name = Some("http"))
        ))

      val tasks = List("192.168.0.1", "192.168.0.2").map { ip =>
        MarathonTestHelper.runningTaskForApp(app.id, app.version, 0, 0)
          .withNetworkInfos(
            Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress(ip))))
          .withHostPorts(Seq.empty)
      }

      val result = EndpointsHelper.appsToEndpointString(TaskTracker.TasksByApp.forTasks(tasks: _*), Seq(app), " ")
      result.trim.shouldBe("""test-app 10000 192.168.0.1:80 192.168.0.2:80""")
    }

    "output the agent IP and host port when hostPort is nonEmpty" in {
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withIpAddress(IpAddress.empty)
        .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
        .withPortMappings(Seq(
          Docker.PortMapping(containerPort = 80, hostPort = Some(0), servicePort = 10000, protocol = "tcp", name = Some("http"))
        ))

      val tasks = List(1010, 1020).map { port =>
        MarathonTestHelper.runningTaskForApp(app.id, app.version, 0, 0)
          .withNetworkInfos(
            Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress("192.168.1.1"))))
          .withHostPorts(List(port))
      }

      val result = EndpointsHelper.appsToEndpointString(TaskTracker.TasksByApp.forTasks(tasks: _*), Seq(app), " ")
      result.trim.shouldBe("""test-app 10000 some.host:1010 some.host:1020""")
    }

    "handle the task hostPorts offset heterogenous hostPort definition properly (empty, nonEmpty)" in {
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withIpAddress(IpAddress.empty)
        .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
        .withPortMappings(Seq(
          Docker.PortMapping(containerPort = 80, hostPort = None, servicePort = 10000, name = Some("http")),
          Docker.PortMapping(containerPort = 9998, hostPort = Some(0), servicePort = 10001, name = Some("service1")),
          Docker.PortMapping(containerPort = 9999, hostPort = Some(0), servicePort = 10002, name = Some("service2"))
        ))

      val tasks = List(List(1010, 1011), List(1020, 1021)).map { ports =>
        MarathonTestHelper.runningTaskForApp(app.id, app.version, 0, 0)
          .withNetworkInfos(
            Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress("192.168.1.1"))))
          .withHostPorts(ports)
      }

      val result = EndpointsHelper.appsToEndpointString(TaskTracker.TasksByApp.forTasks(tasks: _*), Seq(app), " ")
      result.shouldBe(
        "test-app 10000 192.168.1.1:80 192.168.1.1:80 \n" +
          "test-app 10001 some.host:1010 some.host:1020 \n" +
          "test-app 10002 some.host:1011 some.host:1021 \n")
    }
  }

  "does not include mesos containers using ip-per-container (as they lack service ports)" in {
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(IpAddress.empty)

    EndpointsHelper.appsToEndpointString(TaskTracker.TasksByApp.empty, List(app), " ").shouldBe("")
  }

  "renders docker containers using host networking with multiple servicePorts properly" in {
    val app = MarathonTestHelper.makeBasicApp()
      .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.HOST)
      .withPortDefinitions(List(PortDefinition(10000), PortDefinition(10001)))

    val tasks = List(List(1010, 1011), List(1020, 1021)).map { ports =>
      MarathonTestHelper.runningTaskForApp(app.id, app.version, 0, 0)
        .withHostPorts(ports)
    }

    val result = EndpointsHelper.appsToEndpointString(TaskTracker.TasksByApp.forTasks(tasks: _*), Seq(app), " ")
    result.trim.shouldBe("test-app 10000 some.host:1010 some.host:1020 \n" +
      "test-app 10001 some.host:1011 some.host:1021")
  }

  "simply outputs the hosts without ports when no servicePorts are defined" in {
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()

    val tasks = (1 to 2).map { _ =>
      MarathonTestHelper.runningTaskForApp(app.id, app.version, 0, 0)
        .withHostPorts(Nil)
    }

    val result = EndpointsHelper.appsToEndpointString(TaskTracker.TasksByApp.forTasks(tasks: _*), Seq(app), " ")
    result.trim.shouldBe("test-app   some.host some.host")
  }
}
