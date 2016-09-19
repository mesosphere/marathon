package mesosphere.marathon.core.event

import mesosphere.marathon.Protos
import mesosphere.marathon.state.{ MarathonState, Timestamp }

import scala.collection.JavaConverters._

case class EventSubscribers(urls: Set[String] = Set.empty[String])
    extends MarathonState[Protos.EventSubscribers, EventSubscribers] {

  override def mergeFromProto(message: Protos.EventSubscribers): EventSubscribers =
    EventSubscribers(Set(message.getCallbackUrlsList.asScala: _*))

  override def mergeFromProto(bytes: Array[Byte]): EventSubscribers = {
    val proto = Protos.EventSubscribers.parseFrom(bytes)
    mergeFromProto(proto)
  }

  override def toProto: Protos.EventSubscribers = {
    val builder = Protos.EventSubscribers.newBuilder()
    urls.foreach(builder.addCallbackUrls)
    builder.build()
  }

  override def version: Timestamp = Timestamp.zero
}

