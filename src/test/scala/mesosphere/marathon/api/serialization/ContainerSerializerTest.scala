package mesosphere.marathon
package api.serialization

import mesosphere.UnitTest
import mesosphere.marathon.state.Container.PortMapping
import mesosphere.marathon.state.Container

import scala.collection.JavaConverters._
import mesosphere.marathon.core.pod.{ HostNetwork, BridgeNetwork, ContainerNetwork }
import org.scalatest.Inside
import org.apache.mesos.{ Protos => Mesos }

class ContainerSerializerTest extends UnitTest with Inside {
  "network toMesos serializer" when {
    "a single container network is defined" should {
      "assign the portMappings to the only defined network for UCR container" in {
        val networks = List(ContainerNetwork("network"))
        val container = Container.Mesos(Nil, List(PortMapping(hostPort = Some(1000))))

        val result = ContainerSerializer.toMesos(networks, container, "mesos-bridge")

        val Seq(networkInfo) = result.getNetworkInfosList.asScala.toList
        networkInfo.getName shouldBe ("network")
        inside(networkInfo.getPortMappingsList.asScala.toList) {
          case Seq(portMapping) =>
            portMapping.getHostPort shouldBe 1000
        }
      }
      "assign the portMappings to the only defined bridge network for docker container" in {
        val networks = List(BridgeNetwork())
        val container = Container.Docker(portMappings = List(PortMapping(hostPort = Some(1000))))

        val result = ContainerSerializer.toMesos(networks, container, "mesos-bridge")

        result.getNetworkInfosCount shouldBe 0
        result.getDocker.getNetwork shouldBe (Mesos.ContainerInfo.DockerInfo.Network.BRIDGE)
        inside(result.getDocker.getPortMappingsList.asScala.toList) {
          case Seq(portMapping) =>
            portMapping.getHostPort shouldBe 1000
        }
      }
      "assign the portMappings to the only defined container network for docker container" in {
        val networks = List(ContainerNetwork("network"))
        val container = Container.Docker(portMappings = List(PortMapping(hostPort = Some(1000))))

        val result = ContainerSerializer.toMesos(networks, container, "mesos-bridge")

        val Seq(networkInfo) = result.getNetworkInfosList.asScala.toList
        networkInfo.getName shouldBe ("network")
        networkInfo.getPortMappingsCount shouldBe 0

        result.getDocker.getNetwork shouldBe (Mesos.ContainerInfo.DockerInfo.Network.USER)
        inside(result.getDocker.getPortMappingsList.asScala.toList) {
          case Seq(portMapping) =>
            portMapping.getHostPort shouldBe 1000
        }
      }
      "not serialize the network if it's host mode for the docker container" in {
        val networks = List(HostNetwork)
        val container = Container.Docker()

        val result = ContainerSerializer.toMesos(networks, container, "mesos-bridge")

        // We expect the protobuf field to be not set, so we know that it's not serialized.
        result.getDocker.hasNetwork shouldBe false

        // Make sure we get the correct default value back even though the network isn't serialized.
        result.getDocker.getNetwork shouldBe (Mesos.ContainerInfo.DockerInfo.Network.HOST)
      }
    }

    "multiple container networks are defined" should {
      "assign the portMappings to the specified network" in {
        val networks = List(ContainerNetwork("network-1"), ContainerNetwork("network-2"))
        val container = Container.Mesos(Nil, List(
          PortMapping(hostPort = Some(1000), networkNames = List("network-1")),
          PortMapping(hostPort = Some(1001), networkNames = List("network-2"))))

        val result = ContainerSerializer.toMesos(networks, container, "mesos-bridge")

        val Seq(networkInfo1, networkInfo2) = result.getNetworkInfosList.asScala.toList
        networkInfo1.getName shouldBe ("network-1")
        inside(networkInfo1.getPortMappingsList.asScala.toList) {
          case Seq(portMapping) =>
            portMapping.getHostPort shouldBe 1000
        }

        networkInfo2.getName shouldBe ("network-2")
        inside(networkInfo2.getPortMappingsList.asScala.toList) {
          case Seq(portMapping) =>
            portMapping.getHostPort shouldBe 1001
        }
      }
    }
  }
}
