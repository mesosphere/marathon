package mesosphere.marathon
package api.v2.json

import mesosphere.marathon.core.pod.ContainerNetwork
import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.raml
import mesosphere.marathon.raml.Raml

class NetworkTest extends UnitTest with ValidationTestLike {
  val networkValidator = roundTripValidator[raml.Network](None)

  "it should not allow network names with special chars" in {
    val cn = Raml.toRaml(ContainerNetwork(name = "invalid^name"))

    networkValidator(cn) should haveViolations(
      "/name" -> "error.pattern")
  }

  "it should allow network names with underscores, numbers" in {
    val cn = Raml.toRaml(ContainerNetwork(name = "network_1-ops"))
    networkValidator(cn) shouldBe aSuccess
  }
}
