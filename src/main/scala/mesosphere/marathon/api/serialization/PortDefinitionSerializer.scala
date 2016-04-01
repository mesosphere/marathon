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
      val labelsBuilder = mesos.Protos.Labels.newBuilder
      portDefinition.labels
        .map { case (key, value) => mesos.Protos.Label.newBuilder.setKey(key).setValue(value).build }
        .foreach(labelsBuilder.addLabels)
      builder.setLabels(labelsBuilder.build())
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
