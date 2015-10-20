package mesosphere.marathon.event.http

import mesosphere.marathon.Protos
import mesosphere.marathon.state.{ MarathonState, Timestamp }

import scala.collection.JavaConversions._

case class EventSubscribers(urls: Set[String] = Set.empty[String])
    extends MarathonState[Protos.EventSubscribers, EventSubscribers] {

  override def mergeFromProto(message: Protos.EventSubscribers): EventSubscribers =
    EventSubscribers(Set(message.getCallbackUrlsList: _*))

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

