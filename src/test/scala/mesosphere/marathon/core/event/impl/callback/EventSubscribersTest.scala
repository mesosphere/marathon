package mesosphere.marathon
package core.event.impl.callback

import mesosphere.UnitTest
import mesosphere.marathon.core.event.EventSubscribers

class EventSubscribersTest extends UnitTest {
  "EventSubscribers" should {
    "ToProtoEmpty" in {
      val v = new EventSubscribers
      val proto = v.toProto

      assert(proto.getCallbackUrlsCount == 0)
      assert(proto.getCallbackUrlsList.size() == 0)
    }

    "ToProtoNotEmpty" in {
      val v = EventSubscribers(Set("http://localhost:9090/callback"))
      val proto = v.toProto

      assert(proto.getCallbackUrlsCount == 1)
      assert(proto.getCallbackUrlsList.size() == 1)
      assert(proto.getCallbackUrls(0) == "http://localhost:9090/callback")
    }

    "mergeFromProtoEmpty" in {
      val proto = Protos.EventSubscribers.newBuilder().build()
      val subscribers = EventSubscribers()
      val mergeResult = subscribers.mergeFromProto(proto)

      assert(mergeResult.urls == Set.empty[String])
    }

    "mergeFromProtoNotEmpty" in {
      val proto = Protos.EventSubscribers.newBuilder().addCallbackUrls("http://localhost:9090/callback").build()
      val subscribers = EventSubscribers()
      val mergeResult = subscribers.mergeFromProto(proto)

      assert(mergeResult.urls == Set("http://localhost:9090/callback"))
    }
  }
}
