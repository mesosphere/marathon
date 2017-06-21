package mesosphere.marathon
package state

import com.google.common.collect.Lists
import mesosphere.UnitTest
import mesosphere.marathon.Protos.MarathonTask

class MarathonTaskTest extends UnitTest {

  "MarathonTask" should {
    "toProto returns the encapsulated MarathonTask" in {
      Given("A state created from a task")
      val encapsulatedTask = makeTask("app/dummy", 42000, version = Some("123"))
      val state = MarathonTaskState(encapsulatedTask)

      When("We call the toProto function")
      val proto = state.toProto

      Then("The returned proto equals the one passed in")
      proto shouldEqual encapsulatedTask
    }

    "mergeFromProto returns a sane instance" in {
      Given("A state created from a task with version")
      val dummy = makeTask("app/dummy", 42000, version = Some("123"))
      val dummyState = MarathonTaskState(dummy)

      When("We call the mergeFromProto function on that state")
      val proto = makeTask("app/foo", 23000, version = None)
      val merged = dummyState.mergeFromProto(proto)

      Then("The 'merged' state does not have a version because mergeFromProto does not merge but create a new instance based on the given proto")
      merged.toProto shouldEqual proto
    }

    "mergeFromProto bytes returns a sane instance" in {
      Given("A state created from a task with version")
      val dummy = makeTask("app/dummy", 42000, version = Some("123"))
      val dummyState = MarathonTaskState(dummy)

      When("We call the mergeFromProto function using a byte array")
      val proto = makeTask("app/foo", 23000, version = None)
      val merged = dummyState.mergeFromProto(proto.toByteArray)

      Then("The 'merged' state does not have a version because mergeFromProto does not merge but create a new instance based on the given proto")
      merged.toProto shouldEqual proto
    }
  }

  private[this] def makeTask(id: String, port: Int, version: Option[String]) = {
    val builder = MarathonTask.newBuilder()
      .addAllPorts(Lists.newArrayList(port))
      .setId(id)

    version.map(builder.setVersion)
    builder.setCondition(MarathonTask.Condition.Staging)

    builder.build()
  }

}
