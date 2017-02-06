package mesosphere.marathon
package core.task.state

import mesosphere.UnitTest
import mesosphere.marathon.state.Container.{ Docker, Mesos, PortMapping }
import mesosphere.marathon.state._
import org.apache.mesos

class NetworkInfoTest extends UnitTest {
  val f = new Fixture

  "NetworkInfo" when {

    "computing PortAssignments from DiscoveryInfo w/ ipAddress" should {
      "compute the correct values" in {
        val app = AppDefinition(
          id = PathId("test"),
          container = Some(Mesos()),
          ipAddress = Some(IpAddress(
            groups = Seq.empty,
            labels = Map.empty,
            discoveryInfo = DiscoveryInfo(ports = Seq(
              DiscoveryInfo.Port(number = 8080, name = "http", protocol = "tcp"),
              DiscoveryInfo.Port(number = 9000, name = "admin", protocol = "tcp")
            )),
            networkName = Some("network")
          )),
          portDefinitions = Nil // we have to explicitly set portDefinitions to None because the default is [0]
        )
        val networkInfo = NetworkInfo(
          hostName = f.hostName,
          hostPorts = Seq(31000, 31005),
          ipAddresses = Seq(mesos.Protos.NetworkInfo.IPAddress.newBuilder()
            .setIpAddress("10.0.0.42")
            .setProtocol(mesos.Protos.NetworkInfo.Protocol.IPv4)
            .build())
        )

        networkInfo.portAssignments(app) should be(
          Seq(
            PortAssignment(
              portName = Some("http"),
              effectiveIpAddress = Some("10.0.0.42"),
              effectivePort = 31000,
              hostPort = Some(31000),
              containerPort = None),
            PortAssignment(
              portName = Some("admin"),
              effectiveIpAddress = Some("10.0.0.42"),
              effectivePort = 31005,
              hostPort = Some(31005),
              containerPort = None)
          )
        )
      }
    }

    "computing PortAssignments from PortMappings (network mode BRIDGED)" should {
      "compute the correct values" in {
        val app = AppDefinition(
          id = PathId("test"),
          container = Some(Docker(
            network = Some(mesos.Protos.ContainerInfo.DockerInfo.Network.BRIDGE),
            portMappings = Seq(
              PortMapping(
                containerPort = 8080,
                hostPort = Some(0),
                servicePort = 9000,
                protocol = "tcp",
                name = Some("http")
              ),
              PortMapping(
                containerPort = 8081,
                hostPort = None,
                servicePort = 9001,
                protocol = "udp",
                name = Some("admin")
              )
            )
          ))
        )
        val networkInfo = NetworkInfo(
          hostName = f.hostName,
          hostPorts = Seq(20001),
          ipAddresses = Nil
        )
        networkInfo.portAssignments(app) should be(
          Seq(
            PortAssignment(
              portName = Some("http"),
              effectiveIpAddress = Some(f.hostName),
              effectivePort = 20001,
              hostPort = Some(20001),
              containerPort = Some(8080)),
            PortAssignment(
              portName = Some("admin"),
              effectiveIpAddress = Some(f.hostName),
              effectivePort = 8081,
              hostPort = None,
              containerPort = Some(8081))
          )
        )
      }
    }

    "computing PortAssignments from PortMappings (network mode USER)" should {
      "compute the correct values" in {
        val app = AppDefinition(
          id = PathId("test"),
          container = Some(Docker(
            network = Some(mesos.Protos.ContainerInfo.DockerInfo.Network.USER),
            portMappings = Seq(
              PortMapping(containerPort = 0, hostPort = Some(31000), servicePort = 9000, protocol = "tcp"),
              PortMapping(containerPort = 0, hostPort = None, servicePort = 9001, protocol = "tcp"),
              PortMapping(containerPort = 0, hostPort = Some(31005), servicePort = 9002, protocol = "tcp")
            )
          ))
        )

        val networkInfo = NetworkInfo(
          hostName = f.hostName,
          hostPorts = Seq(31000, 31005),
          ipAddresses = Nil
        )

        networkInfo.portAssignments(app) should be(
          Seq(
            PortAssignment(
              portName = None,
              effectiveIpAddress = Some(f.hostName),
              effectivePort = 31000,
              hostPort = Some(31000),
              containerPort = Some(0)),
            PortAssignment(
              portName = None,
              effectiveIpAddress = Some(f.hostName),
              effectivePort = 0,
              hostPort = None,
              containerPort = Some(0)),
            PortAssignment(
              portName = None,
              effectiveIpAddress = Some(f.hostName),
              effectivePort = 31005,
              hostPort = Some(31005),
              containerPort = Some(0))
          )
        )
      }
    }

    "computing PortAssignments from PortDefinitions" should {
      "compute the correct values" in {
        val app = AppDefinition(
          id = PathId("test"),
          container = Some(Mesos()),
          portDefinitions = Seq(
            PortDefinition(
              port = 8080, // this will be a service port
              protocol = "udp,tcp",
              name = Some("http"),
              labels = Map.empty
            ),
            PortDefinition(
              port = 9000, // this will be a service port
              protocol = "tcp",
              name = Some("admin"),
              labels = Map.empty
            )
          )
        )

        val networkInfo = NetworkInfo(
          hostName = f.hostName,
          hostPorts = Seq(31000, 31005),
          ipAddresses = Nil
        )

        networkInfo.portAssignments(app) should be(
          Seq(
            PortAssignment(
              portName = Some("http"),
              effectiveIpAddress = Some(f.hostName),
              effectivePort = 31000,
              hostPort = Some(31000),
              containerPort = None),
            PortAssignment(
              portName = Some("admin"),
              effectiveIpAddress = Some(f.hostName),
              effectivePort = 31005,
              hostPort = Some(31005),
              containerPort = None)
          )
        )

      }
    }
  }

  class Fixture {
    val hostName = "host.some"
  }
}
