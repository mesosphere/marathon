package mesosphere.marathon.core.event.impl.callback

import mesosphere.marathon.core.event.{ EventFilter, EventSubscribers }
import mesosphere.marathon.test.MarathonSpec
import mesosphere.marathon.{ Protos, Seq }

class EventSubscribersTest extends MarathonSpec {

  test("ToProtoEmpty") {
    val v = new EventSubscribers
    val proto = v.toProto

    assert(proto.getCallbackUrlsCount == 0)
    assert(proto.getCallbackUrlsList.size() == 0)
  }

  test("ToProtoNotEmpty") {
    val v = EventSubscribers(Set("http://localhost:9090/callback"))
    val proto = v.toProto

    assert(proto.getCallbackUrlsCount == 0)
    assert(proto.getCallbackUrlsList.size() == 0)
    assert(proto.getCallbackUrlsWithFiltersCount == 1)
    assert(proto.getCallbackUrlsWithFilters(0).getCallbackFiltersCount == 0)
    assert(proto.getCallbackUrlsWithFilters(0).getUrl == "http://localhost:9090/callback")
  }

  test("mergeFromProtoEmpty") {
    val proto = Protos.EventSubscribers.newBuilder().build()
    val subscribers = EventSubscribers()
    val mergeResult = subscribers.mergeFromProto(proto)

    assert(mergeResult.urls == Set.empty[String])
  }

  test("mergeFromProtoNotEmpty Deprecated") {
    val proto = Protos.EventSubscribers.newBuilder().addCallbackUrls("http://localhost:9090/callback").build()
    val subscribers = EventSubscribers()
    val mergeResult = subscribers.mergeFromProto(proto)

    assert(mergeResult.urls == Set("http://localhost:9090/callback"))
  }

  test("mergeFromProtoNotEmpty") {
    val filter = Protos.CallbackFilter.newBuilder()
      .setFilterQuery("query")
      .addAcceptedValues("value 1")
      .addAcceptedValues("value 2")
    val callback = Protos.Callback.newBuilder()
      .setUrl("http://localhost:9090/callback")
      .addCallbackFilters(filter)
    val proto = Protos.EventSubscribers.newBuilder().addCallbackUrlsWithFilters(callback).build()
    val subscribers = EventSubscribers()
    val mergeResult = subscribers.mergeFromProto(proto)

    assert(mergeResult.urls == Set("http://localhost:9090/callback"))
    assert(mergeResult.callbacks == Map("http://localhost:9090/callback" -> Seq(new EventFilter("query", Set("value 1", "value 2")))))
  }
}
