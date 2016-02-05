package mesosphere.marathon.state

import com.google.common.collect.Lists
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.TextAttribute
import org.scalatest.{ GivenWhenThen, Matchers }

class MarathonTaskTest extends MarathonSpec with GivenWhenThen with Matchers {

  test("toProto returns the encapsulated MarathonTask") {
    Given("A state created from a task")
    val encapsulatedTask = makeTask("app/dummy", "dummyhost", 42000, version = Some("123"))
    val state = MarathonTaskState(encapsulatedTask)

    When("We call the toProto function")
    val proto = state.toProto

    Then("The returned proto equals the one passed in")
    proto shouldEqual encapsulatedTask
  }

  test("mergeFromProto returns a sane instance") {
    Given("A state created from a task with version")
    val dummy = makeTask("app/dummy", "dummyhost", 42000, version = Some("123"))
    val dummyState = MarathonTaskState(dummy)

    When("We call the mergeFromProto function on that state")
    val proto = makeTask("app/foo", "superhost", 23000, version = None)
    val merged = dummyState.mergeFromProto(proto)

    Then("The 'merged' state does not have a version because mergeFromProto does not merge but create a new instance based on the given proto")
    merged.toProto shouldEqual proto
  }

  test("mergeFromProto bytes returns a sane instance") {
    Given("A state created from a task with version")
    val dummy = makeTask("app/dummy", "dummyhost", 42000, version = Some("123"))
    val dummyState = MarathonTaskState(dummy)

    When("We call the mergeFromProto function using a byte array")
    val proto = makeTask("app/foo", "superhost", 23000, version = None)
    val merged = dummyState.mergeFromProto(proto.toByteArray)

    Then("The 'merged' state does not have a version because mergeFromProto does not merge but cerate a new instance based on the given proto")
    merged.toProto shouldEqual proto
  }

  private[this] def makeTask(id: String, host: String, port: Int, version: Option[String]) = {
    val builder = MarathonTask.newBuilder()
      .setHost(host)
      .addAllPorts(Lists.newArrayList(port))
      .setId(id)
      .addAttributes(TextAttribute("attr1", "bar"))

    version.map(builder.setVersion)

    builder.build()
  }

}
