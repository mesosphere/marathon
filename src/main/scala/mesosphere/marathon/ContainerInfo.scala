package mesosphere.marathon

import com.google.protobuf.ByteString
import scala.collection.JavaConverters._


case class ContainerInfo(image: String = "", options: Seq[String] = Seq()) {
  def toProto: Protos.ContainerInfo =
    Protos.ContainerInfo.newBuilder()
      .setImage(ByteString.copyFromUtf8(image))
      .addAllOptions(options.map(ByteString.copyFromUtf8(_)).asJava)
      .build()
}

object ContainerInfo {
  def apply(proto: Protos.ContainerInfo): ContainerInfo = ContainerInfo(
    proto.getImage.toStringUtf8,
    proto.getOptionsList.asScala.map(_.toStringUtf8).toSeq
  )
}
