package mesosphere.marathon
package state

import mesosphere.UnitTest
import mesosphere.marathon.core.readiness.ReadinessCheckTestHelper

class ReadinessCheckSerializerTest extends UnitTest {
  "ReadinessCheckSerialization" should {
    "get defaultHttp for empty protobuf" in {
      Given("an empty protobuf")
      val proto = Protos.ReadinessCheckDefinition.getDefaultInstance
      When("reading it")
      val check = ReadinessCheckSerializer.fromProto(proto)
      Then("we get the defaults")
      check should equal(ReadinessCheckTestHelper.defaultHttp)
    }

    "defaultHttp example serialized/deserializes" in {
      Given("a defaultHttp readinessCheck")
      val defaultHttp = ReadinessCheckTestHelper.defaultHttp
      When("serializing it to a proto")
      val proto = ReadinessCheckSerializer.toProto(defaultHttp)
      And("deserializing it again")
      val reread = ReadinessCheckSerializer.fromProto(proto)
      Then("we get the original check back")
      reread should equal(defaultHttp)
    }

    "alternativeHttps example serialized/deserializes" in {
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
}
