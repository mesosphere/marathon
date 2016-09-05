package mesosphere.marathon.api.serialization

import mesosphere.marathon.state.PortDefinition
import org.apache.mesos

import scala.collection.JavaConverters._

object PortDefinitionSerializer {
  def toProto(portDefinition: PortDefinition): mesos.Protos.Port = toProto(portDefinition, split = false).head

  def toMesosProto(portDefinition: PortDefinition): Seq[mesos.Protos.Port] = toProto(portDefinition, split = true)

  private def toProto(portDefinition: PortDefinition, split: Boolean): Seq[mesos.Protos.Port] = {
    val protocols: Seq[String] = if (split) {
      portDefinition.protocol.split(',')
    } else {
      Seq(portDefinition.protocol)
    }
    protocols.map { protocol =>
      val builder = mesos.Protos.Port.newBuilder
        .setNumber(portDefinition.port)
        .setProtocol(protocol)

      portDefinition.name.foreach(builder.setName)

      if (portDefinition.labels.nonEmpty) {
        builder.setLabels(LabelsSerializer.toMesosLabelsBuilder(portDefinition.labels))
      }

      builder.build
    }
  }

  def fromProto(proto: mesos.Protos.Port): PortDefinition = {
    val labels =
      if (proto.hasLabels)
        proto.getLabels.getLabelsList.asScala.map { p => p.getKey -> p.getValue }.toMap
      else Map.empty[String, String]

    PortDefinition(
      proto.getNumber,
      proto.getProtocol,
      if (proto.hasName) Some(proto.getName) else None,
      labels
    )
  }
}
