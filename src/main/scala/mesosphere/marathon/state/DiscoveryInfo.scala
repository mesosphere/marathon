package mesosphere.marathon
package state

import mesosphere.marathon.api.serialization.LabelsSerializer
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.{ Protos => MesosProtos }

case class DiscoveryInfo(ports: Seq[DiscoveryInfo.Port] = Seq.empty) {
  def toProto: Protos.DiscoveryInfo = {
    Protos.DiscoveryInfo.newBuilder
      .addAllPorts(ports.map(_.toProto))
      .build
  }

  def isEmpty: Boolean = DiscoveryInfo.empty.equals(this)
  def nonEmpty: Boolean = !isEmpty
}

object DiscoveryInfo {
  def empty: DiscoveryInfo = DiscoveryInfo()

  def fromProto(proto: Protos.DiscoveryInfo): DiscoveryInfo = {
    DiscoveryInfo(
      proto.getPortsList.map(Port.fromProto)(collection.breakOut)
    )
  }

  case class Port(
      number: Int,
      name: String,
      protocol: String,
      labels: Map[String, String] = Map.empty[String, String]) {
    require(Port.AllowedProtocols(protocol), "protocol can only be 'tcp' or 'udp'")

    def toProto: MesosProtos.Port = {
      val builder = MesosProtos.Port.newBuilder
        .setNumber(number)
        .setName(name)
        .setProtocol(protocol)

      if (labels.nonEmpty) {
        builder.setLabels(LabelsSerializer.toMesosLabelsBuilder(labels))
      }

      builder.build
    }
  }

  object Port {
    val AllowedProtocols: Set[String] = Set("tcp", "udp")

    def fromProto(proto: MesosProtos.Port): Port = {
      val labels: Map[String, String] =
        if (proto.hasLabels)
          proto.getLabels.getLabelsList.map { p => p.getKey -> p.getValue }(collection.breakOut)
        else Map.empty[String, String]

      Port(
        number = proto.getNumber,
        name = proto.getName,
        protocol = proto.getProtocol,
        labels = labels
      )
    }
  }
}
