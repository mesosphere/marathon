package mesosphere.marathon
package api.v2.json

import mesosphere.UnitTest
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.raml.{FixedPodScalingPolicy, RamlSerializer}

class PodScalingPolicyFormatsTest extends UnitTest {

  "Play JSON and Jackson formats" should {
    "yield the same JSON string" in {
      val policy = FixedPodScalingPolicy(4)

      JsonTestHelper.assertThatJsonOf(policy).containsEverythingInJsonString(RamlSerializer.serializer.writeValueAsString(policy))
    }
  }
}
