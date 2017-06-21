package mesosphere.mesos

import mesosphere.UnitTest
import mesosphere.marathon.core.pod.{ BridgeNetwork, ContainerNetwork, HostNetwork }
import mesosphere.marathon.raml.Endpoint
import mesosphere.marathon.state.Container.{ Mesos, PortMapping }
import mesosphere.marathon.state.{ AppDefinition, PathId, PortDefinition }

import scala.collection.immutable.Seq
import mesosphere.mesos.protos.Implicits._

class PortDiscoveryTest extends UnitTest {
  def containerNetworks(qty: Int = 1) = (1 to qty).map{ i => ContainerNetwork(s"network-${i.toString}") }

  "generating for pods" should {
    "generate a network-name label for endpoints specifying a network name and not a host port" in {
      val Seq(discovery) = PortDiscovery.generateForPod(
        containerNetworks(1),
        endpoints = Seq(
          Endpoint(
            name = "service",
            containerPort = Some(80),
            hostPort = None,
            protocol = List("lolocol"),
            networkNames = List("network-1"))))

      discovery.getName shouldBe "service"
      discovery.getProtocol shouldBe "lolocol"
      discovery.getLabels.fromProto shouldBe (
        Map(
          PortDiscovery.NetworkScopeLabel -> PortDiscovery.NetworkScopeContainer,
          PortDiscovery.NetworkNameLabel -> "network-1"))
    }

    "not generate a network-name label for endpoints specifying a host port" in {
      val Seq(discovery) = PortDiscovery.generateForPod(
        containerNetworks(1),
        endpoints = Seq(
          Endpoint(
            name = "service",
            containerPort = Some(80),
            hostPort = Some(1000),
            protocol = List("lolocol"),
            networkNames = List("network-1"))))

      discovery.getLabels.fromProto shouldBe (
        Map(
          PortDiscovery.NetworkScopeLabel -> PortDiscovery.NetworkScopeHost))
    }

    "generate a label for all networks when networkNames is Nil" in {
      val discovery = PortDiscovery.generateForPod(
        containerNetworks(2),
        endpoints = Seq(
          Endpoint(
            name = "service",
            containerPort = Some(80),
            protocol = List("lolocol"),
            networkNames = Nil)))

      discovery.map(_.getLabels.fromProto) shouldBe Seq(
        Map(
          PortDiscovery.NetworkScopeLabel -> PortDiscovery.NetworkScopeContainer,
          PortDiscovery.NetworkNameLabel -> "network-1"),
        Map(
          PortDiscovery.NetworkScopeLabel -> PortDiscovery.NetworkScopeContainer,
          PortDiscovery.NetworkNameLabel -> "network-2"))
    }

    "generate a label for only the specified networkNames" in {
      val discovery = PortDiscovery.generateForPod(
        containerNetworks(3),
        endpoints = Seq(
          Endpoint(
            name = "service",
            containerPort = Some(80),
            protocol = List("lolocol"),
            networkNames = List("network-1", "network-2"))))

      discovery.map(_.getLabels.fromProto) shouldBe Seq(
        Map(
          PortDiscovery.NetworkScopeLabel -> PortDiscovery.NetworkScopeContainer,
          PortDiscovery.NetworkNameLabel -> "network-1"),
        Map(
          PortDiscovery.NetworkScopeLabel -> PortDiscovery.NetworkScopeContainer,
          PortDiscovery.NetworkNameLabel -> "network-2"))
    }
  }

  "generating for apps" when {
    "using container networks" should {
      "generate a network-name label for mappings specifying a network name and not a host port" in {
        val app = AppDefinition(
          PathId("/test"),
          networks = containerNetworks(1),
          container = Some(Mesos(
            portMappings = Seq(PortMapping(
              name = Some("service"),
              containerPort = 80,
              hostPort = None,
              networkNames = List("network-1"))))))

        val Seq(discovery) = PortDiscovery.generateForApp(app, Seq(None))
        discovery.getLabels.fromProto shouldBe (
          Map(
            PortDiscovery.NetworkScopeLabel -> PortDiscovery.NetworkScopeContainer,
            PortDiscovery.NetworkNameLabel -> "network-1"))
      }

      "not generate a network-name label for mappings with a host port" in {
        val app = AppDefinition(
          PathId("/test"),
          networks = containerNetworks(1),
          container = Some(Mesos(
            portMappings = Seq(PortMapping(
              name = Some("service"),
              containerPort = 80,
              hostPort = Some(0),
              networkNames = List("network-1"))))))

        val Seq(discovery) = PortDiscovery.generateForApp(app, Seq(Some(31500)))
        discovery.getLabels.fromProto shouldBe (
          Map(
            PortDiscovery.NetworkScopeLabel -> PortDiscovery.NetworkScopeHost))
      }

      "generate a label for all networks when networkNames is Nil" in {
        val app = AppDefinition(
          PathId("/test"),
          networks = containerNetworks(2),
          container = Some(Mesos(
            portMappings = Seq(PortMapping(
              name = Some("service"),
              containerPort = 80,
              hostPort = None,
              networkNames = Nil)))))
        val discovery = PortDiscovery.generateForApp(app, Seq(None))

        discovery.map(_.getLabels.fromProto) shouldBe Seq(
          Map(
            PortDiscovery.NetworkScopeLabel -> PortDiscovery.NetworkScopeContainer,
            PortDiscovery.NetworkNameLabel -> "network-1"),
          Map(
            PortDiscovery.NetworkScopeLabel -> PortDiscovery.NetworkScopeContainer,
            PortDiscovery.NetworkNameLabel -> "network-2"))
      }

      "generate a label for only the specified networkNames" in {
        val app = AppDefinition(
          PathId("/test"),
          networks = containerNetworks(3),
          container = Some(Mesos(
            portMappings = Seq(PortMapping(
              name = Some("service"),
              containerPort = 80,
              hostPort = None,
              networkNames = List("network-1", "network-2"))))))

        val discovery = PortDiscovery.generateForApp(app, Seq(None))

        discovery.map(_.getLabels.fromProto) shouldBe Seq(
          Map(
            PortDiscovery.NetworkScopeLabel -> PortDiscovery.NetworkScopeContainer,
            PortDiscovery.NetworkNameLabel -> "network-1"),
          Map(
            PortDiscovery.NetworkScopeLabel -> PortDiscovery.NetworkScopeContainer,
            PortDiscovery.NetworkNameLabel -> "network-2"))
      }

      "generate a record for each specified protocol" in {
        val app = AppDefinition(
          PathId("/test"),
          networks = containerNetworks(3),
          container = Some(Mesos(
            portMappings = Seq(PortMapping(
              name = Some("service"),
              containerPort = 80,
              hostPort = None,
              protocol = PortMapping.UDP_TCP,
              networkNames = List("network-1"))))))

        val discovery = PortDiscovery.generateForApp(app, Seq(None))

        discovery.map(_.getProtocol).toSet shouldBe Set(PortMapping.UDP, PortMapping.TCP)
      }
    }

    "using bridge networking" should {
      "generate host DiscoveryInfo records" in {
        val app = AppDefinition(
          PathId("/test"),
          networks = Seq(BridgeNetwork()),
          container = Some(Mesos(
            portMappings = Seq(PortMapping(
              name = Some("service"),
              containerPort = 80,
              hostPort = Some(0),
              protocol = PortMapping.TCP)))))

        val Seq(discovery) = PortDiscovery.generateForApp(app, Seq(Some(1000)))
        discovery.getNumber shouldBe 1000
        discovery.getProtocol shouldBe PortMapping.TCP
        discovery.getName shouldBe "service"
        discovery.getLabels.fromProto shouldBe Map("network-scope" -> "host")
      }
    }

    "using host networking" should {
      "generate host DiscoveryInfo records for all defined protocols" in {
        val labels = Map("VIP" -> "127.0.0.1:8080")
        val app = AppDefinition(
          PathId("/test"),
          networks = Seq(HostNetwork),
          portDefinitions = Seq(
            PortDefinition(0, "tcp,udp", Some("http"), labels)))

        val Seq(result1, result2) = PortDiscovery.generateForApp(app, List(Some(8080)))
        result1.getNumber shouldBe (8080)
        result1.getLabels.fromProto shouldBe labels
        result1.getName shouldBe "http"
        result1.getProtocol shouldBe "tcp"
        result2.getProtocol shouldBe "udp"
      }
    }
  }
}
