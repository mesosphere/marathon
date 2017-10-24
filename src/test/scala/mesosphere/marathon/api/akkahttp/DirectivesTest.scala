package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.marathon.api.akkahttp.EntityMarshallers.ValidationFailed
import mesosphere.marathon.core.instance.Instance

class DirectivesTest extends UnitTest with ScalatestRouteTest {

  import Directives._

  "Directives.extractInstanceId" should {
    "reject and invalid id" in {
      Get("invalid-instance-id") ~>  foo { instanceId: Instance.Id =>
        complete("")
      } ~> check {
        rejection shouldBe a[ValidationFailed]
      }
    }

    "return an id" in {

    }
  }
}
