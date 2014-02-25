package mesosphere.marathon.event.http

import org.junit.Test
import org.junit.Assert._
import mesosphere.marathon.Protos

class EventSubscribersTest {

  @Test
  def testToProtoEmpty() {
    val v = new EventSubscribers
    val proto = v.toProto

    assertEquals(proto.getCallbackUrlsCount, 0)
    assertEquals(proto.getCallbackUrlsList.size(), 0)
  }

  @Test
  def testToProtoNotEmpty() {
    val v = new EventSubscribers(Set("http://localhost:9090/callback"))
    val proto = v.toProto

    assertEquals(proto.getCallbackUrlsCount, 1)
    assertEquals(proto.getCallbackUrlsList.size(), 1)
    assertEquals(proto.getCallbackUrls(0), "http://localhost:9090/callback")
  }

  @Test
  def mergeFromProtoEmpty() {
    val proto = Protos.EventSubscribers.newBuilder().build()
    val subscribers = EventSubscribers()
    val mergeResult = subscribers.mergeFromProto(proto)

    assertEquals(mergeResult.urls, Set.empty[String])
  }

  @Test
  def mergeFromProtoNotEmpty() {
    val proto = Protos.EventSubscribers.newBuilder().addCallbackUrls("http://localhost:9090/callback").build()
    val subscribers = EventSubscribers()
    val mergeResult = subscribers.mergeFromProto(proto)

    assertEquals(mergeResult.urls, Set("http://localhost:9090/callback"))
  }
}
