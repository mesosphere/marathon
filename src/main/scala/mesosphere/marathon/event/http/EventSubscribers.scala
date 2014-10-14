package mesosphere.marathon.event.http

import mesosphere.marathon.state.MarathonState
import mesosphere.marathon.Protos
import collection.JavaConversions._

import mesosphere.marathon.api.validation.FieldConstraints.FieldJsonProperty
import mesosphere.marathon.Protos.StorageVersion

case class EventSubscribers(
  @FieldJsonProperty("callbackUrls") urls: Set[String] = Set.empty[String])
    extends MarathonState[Protos.EventSubscribers, EventSubscribers] {

  override def mergeFromProto(message: Protos.EventSubscribers): EventSubscribers =
    EventSubscribers(Set(message.getCallbackUrlsList: _*))

  override def mergeFromProto(bytes: Array[Byte]): EventSubscribers = {
    val proto = Protos.EventSubscribers.parseFrom(bytes)
    mergeFromProto(proto)
  }

  override def toProto: Protos.EventSubscribers = {
    val builder = Protos.EventSubscribers.newBuilder()
    urls.foreach(builder.addCallbackUrls(_))
    builder.build()
  }
}

