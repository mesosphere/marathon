package mesosphere.marathon.state

import mesosphere.marathon.core.readiness.ReadinessCheckTestHelper
import org.scalatest.{ GivenWhenThen, Matchers, FunSuite }
import mesosphere.marathon.Protos

class ReadinessCheckSerializerTest extends FunSuite with Matchers with GivenWhenThen {
  test("get defaultHttp for empty protobuf") {
    Given("an empty protobuf")
    val proto = Protos.ReadinessCheckDefinition.getDefaultInstance
    When("reading it")
    val check = ReadinessCheckSerializer.fromProto(proto)
    Then("we get the defaults")
    check should equal(ReadinessCheckTestHelper.defaultHttp)
  }

  test("defaultHttp example serialized/deserializes") {
    Given("a defaultHttp readinessCheck")
    val defaultHttp = ReadinessCheckTestHelper.defaultHttp
    When("serializing it to a proto")
    val proto = ReadinessCheckSerializer.toProto(defaultHttp)
    And("deserializing it again")
    val reread = ReadinessCheckSerializer.fromProto(proto)
    Then("we get the original check back")
    reread should equal(defaultHttp)
  }

  test("alternativeHttps example serialized/deserializes") {
    Given("a alternativeHttps readinessCheck")
    val alternativeHttps = ReadinessCheckTestHelper.alternativeHttps
    When("serializing it to a proto")
    val proto = ReadinessCheckSerializer.toProto(alternativeHttps)
    And("deserializing it again")
    val reread = ReadinessCheckSerializer.fromProto(proto)
    Then("we get the original check back")
    reread should equal(alternativeHttps)
  }
}
