package mesosphere.marathon

import com.google.protobuf.ByteString
import scala.collection.JavaConverters._


class ContainerInfo protected (val image: String, val options: Seq[String]) {
  def toProto: Protos.ContainerInfo =
    Protos.ContainerInfo.newBuilder()
      .setImage(ByteString.copyFromUtf8(image))
      .addAllOptions(options.map(ByteString.copyFromUtf8).asJava)
      .build()
}

case object EmptyContainerInfo extends ContainerInfo("", Nil)

object ContainerInfo {
  def apply(proto: Protos.ContainerInfo): ContainerInfo = ContainerInfo(
    proto.getImage.toStringUtf8,
    proto.getOptionsList.asScala.map(_.toStringUtf8).toSeq
  )

  def apply(image: String = "", options: Seq[String] = Nil): ContainerInfo = {
    if (image.isEmpty && options.isEmpty)
      EmptyContainerInfo
    else
      new ContainerInfo(image, options)
  }
}
