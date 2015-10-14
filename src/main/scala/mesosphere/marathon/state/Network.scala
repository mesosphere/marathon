package mesosphere.marathon.state

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import org.apache.mesos.{ Protos => mesos }

case class Network(
    ipAddress: Option[String] = None,
    protocol: Option[mesos.NetworkInfo.Protocol] = None,
    groups: Seq[String] = Nil,
    labels: Map[String, String] = Map.empty[String, String]) {

  def toProto(): mesos.NetworkInfo = {
    val builder = mesos.NetworkInfo.newBuilder
    for (a <- ipAddress) builder.setIpAddress(a)
    for (p <- protocol) builder.setProtocol(p)
    builder.addAllGroups(groups.asJava)

    val networkLabels =
      labels.map {
        case (key, value) =>
          mesos.Label.newBuilder
            .setKey(key)
            .setValue(value)
            .build
      }

    builder.setLabels(
      mesos.Labels.newBuilder
        .addAllLabels(networkLabels.asJava)
        .build)

    builder.build
  }
}

object Network {

  object Empty extends Network

  def apply(proto: mesos.NetworkInfo): Network = {

    val labels =
      if (proto.hasLabels) {
        proto.getLabels.getLabelsList.asScala
          .map { p => p.getKey -> p.getValue }
          .toMap
      }
      else Map.empty[String, String]

    Network(
      ipAddress = if (proto.hasIpAddress) Some(proto.getIpAddress) else None,
      protocol = if (proto.hasProtocol) Some(proto.getProtocol) else None,
      groups = proto.getGroupsList.asScala.toIndexedSeq,
      labels = labels
    )
  }

}
