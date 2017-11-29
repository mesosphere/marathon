package mesosphere.mesos.client

import akka.http.scaladsl.model.MediaType.Compressible
import akka.http.scaladsl.model.{ContentType, MediaType, headers}

object MesosStreamSupport {

  val MesosStreamIdHeaderName = "Mesos-Stream-Id"
  def MesosStreamIdHeader(streamId: String) = headers.RawHeader("Mesos-Stream-Id", streamId)
  val ProtobufMediaType: MediaType.Binary = MediaType.applicationBinary("x-protobuf", Compressible)
  val ProtobufContentType: ContentType = ContentType.Binary(ProtobufMediaType)
}