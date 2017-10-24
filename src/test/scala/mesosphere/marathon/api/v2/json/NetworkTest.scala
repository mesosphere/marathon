package mesosphere.marathon
package api.v2.json

import mesosphere.marathon.core.pod.{ ContainerNetwork, Network }
import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.raml
import mesosphere.marathon.raml.Raml
import play.api.libs.json._

class NetworkTest extends UnitTest with ValidationTestLike {
  def toJsonAndBack(c: Network): Network = {
    Raml.fromRaml(
      Json.toJson(Raml.toRaml(c)).as[raml.Network])
  }

  "it should not allow network names with special chars" in {
    a[JsResultException] shouldBe thrownBy {
      toJsonAndBack(ContainerNetwork(name = "invalid^name"))
    }
  }

  "it should allow network names with underscores, numbers" in {
    val original = ContainerNetwork(name = "network_1-ops")
    toJsonAndBack(original) shouldBe original
  }
}
