package mesosphere.marathon.api.serialization

import mesosphere.marathon.state.PortDefinition
import org.apache.mesos

import scala.collection.JavaConverters._

object PortDefinitionSerializer {
  def toProto(portDefinition: PortDefinition): mesos.Protos.Port = {
    val builder = mesos.Protos.Port.newBuilder
      .setNumber(portDefinition.port)
      .setProtocol(portDefinition.protocol)

    portDefinition.name.foreach(builder.setName)

    if (portDefinition.labels.nonEmpty) {
      builder.setLabels(LabelsSerializer.toMesosLabelsBuilder(portDefinition.labels))
    }

    builder.build
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
