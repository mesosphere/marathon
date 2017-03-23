package mesosphere.marathon
package core.event

import mesosphere.marathon.state.{ MarathonState, Timestamp }
import mesosphere.marathon.stream.Implicits._

case class EventSubscribers(urls: Set[String] = Set.empty[String])
    extends MarathonState[Protos.EventSubscribers, EventSubscribers] {

  override def mergeFromProto(message: Protos.EventSubscribers): EventSubscribers =
    EventSubscribers(message.getCallbackUrlsList.toSet)

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

