package mesosphere.marathon.state

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._
import org.apache.mesos.{ Protos => mesos }

case class IPSpecification(protocol: Option[mesos.NetworkInfo.Protocol]) {
  def toProto: mesos.NetworkInfo.IPAddress = {
    val builder = mesos.NetworkInfo.IPAddress.newBuilder()
    protocol.foreach(builder.setProtocol)
    builder.build()
  }
}

object IPSpecification {
  def fromProto(proto: mesos.NetworkInfo.IPAddress): IPSpecification = IPSpecification (
    protocol = if (proto.hasProtocol) Some(proto.getProtocol) else None
  )
}

case class NetworkInterface(
    ipAddresses: Seq[IPSpecification] = Nil,
    groups: Seq[String] = Nil,
    labels: Map[String, String] = Map.empty[String, String]) {

  def toProto: mesos.NetworkInfo = {
    val builder = mesos.NetworkInfo.newBuilder
    ipAddresses.map(_.toProto).foreach(builder.addIpAddresses)
    groups.foreach(builder.addGroups)

    val networkLabels = labels.map { case (key, value) => mesos.Label.newBuilder.setKey(key).setValue(value).build }
    builder.setLabels(mesos.Labels.newBuilder.addAllLabels(networkLabels.asJava).build)

    builder.build
  }
}

object NetworkInterface {

  object Empty extends NetworkInterface

  def fromProto(proto: mesos.NetworkInfo): NetworkInterface = {
    val labels =
      if (proto.hasLabels) proto.getLabels.getLabelsList.asScala.map { p => p.getKey -> p.getValue }.toMap
      else Map.empty[String, String]

    NetworkInterface(
      ipAddresses = proto.getIpAddressesList.asScala.toIndexedSeq.map(IPSpecification.fromProto),
      groups = proto.getGroupsList.asScala.toIndexedSeq,
      labels = labels
    )
  }
}
