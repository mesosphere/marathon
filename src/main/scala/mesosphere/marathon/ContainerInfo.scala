package mesosphere.marathon

import scala.collection.JavaConverters._

import org.apache.mesos.{ Protos => mesos }

class ContainerInfo protected (val image: String, val options: Seq[String]) {
  def toProto: mesos.CommandInfo.ContainerInfo =
    mesos.CommandInfo.ContainerInfo.newBuilder()
      .setImage(image)
      .addAllOptions(options.asJava)
      .build()
}

case object EmptyContainerInfo extends ContainerInfo("", Nil)

object ContainerInfo {
  def apply(proto: mesos.CommandInfo.ContainerInfo): ContainerInfo =
    ContainerInfo(proto.getImage, proto.getOptionsList.asScala.toSeq)

  def apply(image: String = "", options: Seq[String] = Nil): ContainerInfo = {
    if (image.isEmpty && options.isEmpty)
      EmptyContainerInfo
    else
      new ContainerInfo(image, options)
  }
}
