package mesosphere.marathon
package api

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ Container, PortDefinition }
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{ IpAddress }
import org.apache.mesos.Protos

import scala.collection.immutable.Seq

class EndpointsHelperTest extends UnitTest {
  import MarathonTestHelper.Implicits._

  "rendering docker containers with ip-per-container" should {

    "output the container port and container IP when hostPort is empty" in {
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withIpAddress(IpAddress.empty)
        .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
        .withPortMappings(Seq(Container.PortMapping(
          containerPort = 80, hostPort = None, servicePort = 10000, protocol = "tcp", name = Some("http"))))

      val instances = List("192.168.0.1", "192.168.0.2").map { ip =>
        TestInstanceBuilder.newBuilder(app.id)
          .addTaskWithBuilder()
          .taskRunning(None)
          .withNetworkInfo(
            None,
            Seq.empty,
            Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress(ip))))
          .build
          .instance
      }

      val result = EndpointsHelper.appsToEndpointString(
        InstanceTracker.InstancesBySpec.forInstances(instances: _*), Seq(app), " ")

      result.trim.shouldBe("""test-app 10000 192.168.0.1:80 192.168.0.2:80""")
    }

    "output the agent IP and host port when hostPort is nonEmpty" in {
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withIpAddress(IpAddress.empty)
        .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
        .withPortMappings(Seq(
          Container.PortMapping(containerPort = 80, hostPort = Some(0), servicePort = 10000, protocol = "tcp", name = Some("http"))
        ))

      val instances = List(1010, 1020).map { port =>
        TestInstanceBuilder.newBuilder(app.id)
          .addTaskWithBuilder
          .taskRunning(None)
          .withNetworkInfo(
            None,
            List(port),
            Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress("192.168.1.1"))))
          .build
          .instance
      }

      val result = EndpointsHelper.appsToEndpointString(InstanceTracker.InstancesBySpec.forInstances(instances: _*), Seq(app), " ")
      result.trim.shouldBe("""test-app 10000 host.some:1010 host.some:1020""")
    }

    "handle the task hostPorts offset heterogenous hostPort definition properly (empty, nonEmpty)" in {
      val app = MarathonTestHelper.makeBasicApp()
        .withNoPortDefinitions()
        .withIpAddress(IpAddress.empty)
        .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
        .withPortMappings(Seq(
          Container.PortMapping(containerPort = 80, hostPort = None, servicePort = 10000, name = Some("http")),
          Container.PortMapping(containerPort = 9998, hostPort = Some(0), servicePort = 10001, name = Some("service1")),
          Container.PortMapping(containerPort = 9999, hostPort = Some(0), servicePort = 10002, name = Some("service2"))
        ))

      val instances = List(List(1010, 1011), List(1020, 1021)).map { ports =>
        TestInstanceBuilder.newBuilder(app.id)
          .addTaskWithBuilder
          .taskRunning(None)
          .withNetworkInfo(
            None,
            ports,
            Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress("192.168.1.1"))))
          .build
          .instance
      }

      val result = EndpointsHelper.appsToEndpointString(InstanceTracker.InstancesBySpec.forInstances(instances: _*), Seq(app), " ")
      result.shouldBe(
        "test-app 10000 192.168.1.1:80 192.168.1.1:80 \n" +
          "test-app 10001 host.some:1010 host.some:1020 \n" +
          "test-app 10002 host.some:1011 host.some:1021 \n")
    }
  }

  "does not include mesos containers using ip-per-container (as they lack service ports)" in {
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(IpAddress.empty)

    EndpointsHelper.appsToEndpointString(InstanceTracker.InstancesBySpec.empty, List(app), " ").shouldBe("")
  }

  "renders docker containers using host networking with multiple servicePorts properly" in {
    val app = MarathonTestHelper.makeBasicApp()
      .withDockerNetwork(Protos.ContainerInfo.DockerInfo.Network.HOST)
      .withPortDefinitions(List(PortDefinition(10000), PortDefinition(10001)))

    val instances = List(List(1010, 1011), List(1020, 1021)).map { ports =>
      TestInstanceBuilder.newBuilder(app.id)
        .addTaskWithBuilder()
        .taskRunning(None)
        .withNetworkInfo(None, ports, Nil)
        .build
        .instance
    }

    val result = EndpointsHelper.appsToEndpointString(InstanceTracker.InstancesBySpec.forInstances(instances: _*), Seq(app), " ")
    result.trim.shouldBe("test-app 10000 host.some:1010 host.some:1020 \n" +
      "test-app 10001 host.some:1011 host.some:1021")
  }

  "simply outputs the hosts without ports when no servicePorts are defined" in {
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()

    val instances = (1 to 2).map { _ =>
      TestInstanceBuilder.newBuilder(app.id)
        .addTaskWithBuilder()
        .taskRunning(None)
        .withNetworkInfo(None, Nil, Nil)
        .build
        .instance
    }

    val result = EndpointsHelper.appsToEndpointString(InstanceTracker.InstancesBySpec.forInstances(instances: _*), Seq(app), " ")
    result.trim.shouldBe("test-app   host.some host.some")
  }
}
