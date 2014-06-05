package mesosphere.marathon.event.http

import mesosphere.marathon.{ MarathonSpec, Protos }

class EventSubscribersTest extends MarathonSpec {

  test("ToProtoEmpty") {
    val v = new EventSubscribers
    val proto = v.toProto

    assert(proto.getCallbackUrlsCount == 0)
    assert(proto.getCallbackUrlsList.size() == 0)
  }

  test("ToProtoNotEmpty") {
    val v = new EventSubscribers(Set("http://localhost:9090/callback"))
    val proto = v.toProto

    assert(proto.getCallbackUrlsCount == 1)
    assert(proto.getCallbackUrlsList.size() == 1)
    assert(proto.getCallbackUrls(0) == "http://localhost:9090/callback")
  }

  test("mergeFromProtoEmpty") {
    val proto = Protos.EventSubscribers.newBuilder().build()
    val subscribers = EventSubscribers()
    val mergeResult = subscribers.mergeFromProto(proto)

    assert(mergeResult.urls == Set.empty[String])
  }

  test("mergeFromProtoNotEmpty") {
    val proto = Protos.EventSubscribers.newBuilder().addCallbackUrls("http://localhost:9090/callback").build()
    val subscribers = EventSubscribers()
    val mergeResult = subscribers.mergeFromProto(proto)

    assert(mergeResult.urls == Set("http://localhost:9090/callback"))
  }
}
