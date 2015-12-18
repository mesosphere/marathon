package mesosphere.marathon.state

import scala.collection.JavaConverters._
import mesosphere.marathon.Protos
import org.apache.mesos.{ Protos => MesosProtos }

case class DiscoveryInfo(ports: Seq[DiscoveryInfo.Port] = Seq.empty) {
  def toProto: Protos.DiscoveryInfo = {
    Protos.DiscoveryInfo.newBuilder
      .addAllPorts(ports.map(_.toProto).asJava)
      .build
  }
}

object DiscoveryInfo {
  def empty: DiscoveryInfo = DiscoveryInfo()

  def fromProto(proto: Protos.DiscoveryInfo): DiscoveryInfo = {
    DiscoveryInfo(
      proto.getPortsList.asScala.map(Port.fromProto).toList
    )
  }

  case class Port(number: Int, name: String, protocol: String) {
    require(Port.AllowedProtocols(protocol), "protocol can only be 'tcp' or 'udp'")

    def toProto: MesosProtos.Port = {
      MesosProtos.Port.newBuilder
        .setNumber(number)
        .setName(name)
        .setProtocol(protocol)
        .build
    }
  }

  object Port {
    val AllowedProtocols: Set[String] = Set("tcp", "udp")

    def fromProto(proto: MesosProtos.Port): Port = {
      Port(
        number = proto.getNumber,
        name = proto.getName,
        protocol = proto.getProtocol
      )
    }
  }
}
