package mesosphere.marathon
package core.task.state

import mesosphere.UnitTest
import mesosphere.marathon.core.pod.{ BridgeNetwork, ContainerNetwork }
import mesosphere.marathon.state.Container.{ Docker, Mesos, PortMapping }
import mesosphere.marathon.state._

class NetworkInfoTest extends UnitTest {
  val f = new Fixture

  private def ipv4Address(addr: String) = org.apache.mesos.Protos.NetworkInfo.IPAddress.newBuilder()
    .setIpAddress(addr)
    .setProtocol(org.apache.mesos.Protos.NetworkInfo.Protocol.IPv4)
    .build()

  "NetworkInfo" when {
    "computing PortAssignments from PortMappings (network mode BRIDGED)" should {

      val app = AppDefinition(
        id = PathId("test"),
        networks = Seq(BridgeNetwork()), container = Some(Docker(
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
              hostPort = Some(123),
              servicePort = 9001,
              protocol = "udp",
              name = Some("admin")
            )
          )
        ))
      )
      val networkInfo = NetworkInfo(
        hostName = f.hostName,
        hostPorts = Seq(20001, 123),
        ipAddresses = Nil
      )
      "work without an IP address" in {
        networkInfo.portAssignments(app, includeUnresolved = true) should be(
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
              effectivePort = 123,
              hostPort = Some(123),
              containerPort = Some(8081))
          )
        )
      }
      "ignore the IP address when it's available" in {
        val networkInfoWithIp = networkInfo.copy(f.hostName, ipAddresses = Seq(ipv4Address(f.containerIp)))
        networkInfoWithIp.portAssignments(app, includeUnresolved = true) should be(
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
              effectivePort = 123,
              hostPort = Some(123),
              containerPort = Some(8081))
          )
        )
      }
    }

    "computing PortAssignments from PortMappings (network mode USER)" should {

      val app = AppDefinition(
        id = PathId("test"),
        networks = Seq(ContainerNetwork("whatever")), container = Some(Docker(

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

      "work without an IP address" in {
        networkInfo.portAssignments(app, includeUnresolved = true) should be(
          Seq(
            PortAssignment(
              portName = None,
              effectiveIpAddress = Option(f.hostName),
              effectivePort = 31000,
              hostPort = Some(31000),
              containerPort = Some(0)),
            PortAssignment(
              portName = None,
              effectiveIpAddress = None,
              effectivePort = PortAssignment.NoPort,
              hostPort = None,
              containerPort = Some(0)),
            PortAssignment(
              portName = None,
              effectiveIpAddress = Option(f.hostName),
              effectivePort = 31005,
              hostPort = Some(31005),
              containerPort = Some(0))
          )
        )
      }
      "use an IP address when it's available" in {
        val networkInfoWithIp = networkInfo.copy(f.hostName, ipAddresses = Seq(ipv4Address(f.containerIp)))
        networkInfoWithIp.portAssignments(app, includeUnresolved = true) should be(
          Seq(
            PortAssignment(
              portName = None,
              effectiveIpAddress = Some(f.hostName),
              effectivePort = 31000,
              hostPort = Some(31000),
              containerPort = Some(0)),
            PortAssignment(
              portName = None,
              effectiveIpAddress = Some(f.containerIp),
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

        networkInfo.portAssignments(app, includeUnresolved = true) should be(
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
    val containerIp = "10.0.0.42"
  }
}
