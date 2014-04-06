package mesosphere.marathon.event.exec

import org.junit.Test
import org.junit.Assert._
import mesosphere.marathon.Protos

class EventSubscribersTest {

  @Test
  def testToProtoEmpty() {
    val v = new EventSubscribers
    val proto = v.toProto

    assertEquals(proto.getExecCmdsCount, 0)
    assertEquals(proto.getExecCmdsList.size(), 0)
  }

  @Test
  def testToProtoNotEmpty() {
    val v = new EventSubscribers(Set("tee /tmp/marathon-event"))
    val proto = v.toProto

    assertEquals(proto.getExecCmdsCount, 1)
    assertEquals(proto.getExecCmdsList.size(), 1)
    assertEquals(proto.getExecCmds(0), "tee /tmp/marathon-event")
  }

  @Test
  def mergeFromProtoEmpty() {
    val proto = Protos.EventSubscribers.newBuilder().build()
    val subscribers = EventSubscribers()
    val mergeResult = subscribers.mergeFromProto(proto)

    assertEquals(mergeResult.cmds, Set.empty[String])
  }

  @Test
  def mergeFromProtoNotEmpty() {
    val proto = Protos.EventSubscribers.newBuilder().addExecCmds("tee /tmp/marathon-event").build()
    val subscribers = EventSubscribers()
    val mergeResult = subscribers.mergeFromProto(proto)

    assertEquals(mergeResult.cmds, Set("tee /tmp/marathon-event"))
  }
}
