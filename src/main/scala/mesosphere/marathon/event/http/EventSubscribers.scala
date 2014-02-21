package mesosphere.marathon.event.http

import mesosphere.marathon.state.MarathonState
import mesosphere.marathon.Protos
import collection.JavaConversions._

class EventSubscribers(var callback_urls: Set[String] = Set.empty[String])
  extends MarathonState[Protos.EventSubscribers]{

  override def mergeFromProto(message: Protos.EventSubscribers): Unit = {
    callback_urls = message.getCallbackUrlsList.foldLeft(Set.empty[String])((s,url) => s + url)
  }

  override def mergeFromProto(bytes: Array[Byte]): Unit = {
    val proto = Protos.EventSubscribers.parseFrom(bytes)
    mergeFromProto(proto)
  }

  override def toProto: Protos.EventSubscribers = {
    val builder = Protos.EventSubscribers.newBuilder()
    callback_urls.foreach(builder.addCallbackUrls(_))
    builder.build()
  }
}
