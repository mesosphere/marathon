package mesosphere.marathon

import scala.collection.JavaConverters._

import org.apache.mesos.{ Protos => mesos }

case class ContainerInfo(image: String = "", options: Seq[String] = Nil) {
  def toProto: mesos.CommandInfo.ContainerInfo =
    mesos.CommandInfo.ContainerInfo.newBuilder()
      .setImage(image)
      .addAllOptions(options.asJava)
      .build()
}

object EmptyContainerInfo extends ContainerInfo("", Nil)

object ContainerInfo {
  def apply(proto: mesos.CommandInfo.ContainerInfo): ContainerInfo =
    ContainerInfo(proto.getImage, proto.getOptionsList.asScala.toSeq)
}
