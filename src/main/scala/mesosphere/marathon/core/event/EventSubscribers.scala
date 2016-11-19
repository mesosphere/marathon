package mesosphere.marathon
package core.event

import mesosphere.marathon.state.{ MarathonState, Timestamp }
import mesosphere.marathon.stream._

case class EventSubscribers(callbacks: Map[String, Seq[EventFilter]] = Map.empty[String, Seq[EventFilter]])
    extends MarathonState[Protos.EventSubscribers, EventSubscribers] {

  override def mergeFromProto(message: Protos.EventSubscribers): EventSubscribers = {

    val callbacksWithoutFilter = message.getCallbackUrlsList.map(callback => callback -> EventFilters.empty)
    val callbacksWithFilters = message.getCallbackUrlsWithFiltersList.map(callback => {
      callback.getUrl -> callback.getCallbackFiltersList.map(EventFilter.fromProto)
    })

    val callbacks = callbacksWithoutFilter.toSeq ++ callbacksWithFilters.toSeq
    val grouped = callbacks.groupBy(_._1)
    val cleaned = grouped.mapValues(_.flatMap(_._2))
    val transformed = cleaned.map(x => x._1 -> Seq(x._2: _*))

    EventSubscribers(transformed)
  }

  override def mergeFromProto(bytes: Array[Byte]): EventSubscribers = {
    val proto = Protos.EventSubscribers.parseFrom(bytes)
    mergeFromProto(proto)
  }

  override def toProto: Protos.EventSubscribers = {
    val builder = Protos.EventSubscribers.newBuilder()
    callbacks.foreach(callback => {
      val b = Protos.Callback.newBuilder()
      b.setUrl(callback._1)
      b.addAllCallbackFilters(callback._2.map(_.toProto))
      builder.addCallbackUrlsWithFilters(b)
    })
    builder.build()
  }

  override def version: Timestamp = Timestamp.zero

  def urls: Set[String] = callbacks.keySet

  def subscribedUrls(e: MarathonEvent): Iterable[String] = {
    callbacks.filter(_._2.forall(_.accept(e))).keys
  }

}

object EventSubscribers {
  def apply(urls: Set[String]): EventSubscribers = new EventSubscribers(urls.map(url => url -> EventFilters.empty).toMap)
}

case class EventFilter(query: String = "", values: Set[String] = Set.empty[String])
    extends MarathonState[Protos.CallbackFilter, EventFilter] {

  override def mergeFromProto(message: Protos.CallbackFilter): EventFilter =
    EventFilter(message.getFilterQuery, message.getAcceptedValuesList.toSet)

  override def mergeFromProto(bytes: Array[Byte]): EventFilter = {
    val proto = Protos.CallbackFilter.parseFrom(bytes)
    mergeFromProto(proto)
  }

  override def toProto: Protos.CallbackFilter = {
    val builder = Protos.CallbackFilter.newBuilder()
    builder.setFilterQuery(query)
    builder.addAllAcceptedValues(values)
    builder.build()
  }

  override def version: Timestamp = Timestamp.zero

  def accept(e: MarathonEvent): Boolean = {
    //TODO(janisz) handle other events fields (e.g. with jsonpath)
    query == "event_type" && values.contains(e.eventType)
  }
}

object EventFilter {
  def fromProto(message: Protos.CallbackFilter): EventFilter =
    EventFilter(message.getFilterQuery, message.getAcceptedValuesList.toSet)

  def fromProto(bytes: Array[Byte]): EventFilter = {
    val proto = Protos.CallbackFilter.parseFrom(bytes)
    fromProto(proto)
  }
}

object EventFilters {
  def empty: Seq[EventFilter] = Seq.empty
}