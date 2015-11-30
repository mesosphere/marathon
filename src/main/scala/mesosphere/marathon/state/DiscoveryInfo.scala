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
  object Empty extends DiscoveryInfo

  def fromProto(proto: Protos.DiscoveryInfo): DiscoveryInfo = {
    DiscoveryInfo(
      proto.getPortsList.asScala.map(Port.fromProto)
    )
  }

  case class Port(number: Int, name: String, protocol: Port.Protocol) {
    def toProto: MesosProtos.Port = {
      MesosProtos.Port.newBuilder
        .setNumber(number)
        .setName(name)
        .setProtocol(protocol.protoString)
        .build
    }
  }

  object Port {
    def fromProto(proto: MesosProtos.Port): Port = {
      Port(
        number = proto.getNumber,
        name = proto.getName,
        protocol = Protocol.fromProto(proto.getProtocol)
      )
    }

    sealed trait Protocol {
      def protoString: String
    }

    object Protocol {
      val all: Set[Protocol] = Set(TCP, UDP)
      private[this] val fromProtoString: Map[String, Protocol] = all.map(protocol => protocol.protoString -> protocol).toMap
      def fromProto(protoString: String): Protocol = {
        fromProtoString.getOrElse(protoString, throw new IllegalArgumentException(s"Invalid protocol: $protoString"))
      }
    }

    case object TCP extends Protocol {
      override def protoString: String = "tcp"
    }
    case object UDP extends Protocol {
      override def protoString: String = "udp"
    }
  }
}
