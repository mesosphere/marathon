package mesosphere.marathon.state

import mesosphere.marathon.Protos

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import org.apache.mesos.{ Protos => mesos }

case class IpAddress(
    groups: Seq[String] = Nil,
    labels: Map[String, String] = Map.empty[String, String],
    discoveryInfo: DiscoveryInfo = DiscoveryInfo.empty) {

  def toProto: Protos.IpAddress = {
    val builder = Protos.IpAddress.newBuilder
    groups.foreach(builder.addGroups)
    labels
      .map { case (key, value) => mesos.Label.newBuilder.setKey(key).setValue(value).build }
      .foreach(builder.addLabels)
    builder.setDiscoveryInfo(discoveryInfo.toProto)
    builder.build
  }
}

object IpAddress {
  object Empty extends IpAddress

  def fromProto(proto: Protos.IpAddress): IpAddress = {
    IpAddress(
      groups = proto.getGroupsList.asScala.toIndexedSeq,
      labels = proto.getLabelsList.asScala.map { p => p.getKey -> p.getValue }.toMap,
      discoveryInfo =
        if (proto.hasDiscoveryInfo) DiscoveryInfo.fromProto(proto.getDiscoveryInfo)
        else DiscoveryInfo.empty
    )
  }
}
