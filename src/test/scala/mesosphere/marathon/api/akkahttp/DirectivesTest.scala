package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.api.akkahttp.EntityMarshallers.ValidationFailed
import mesosphere.marathon.core.instance.Instance
import org.scalatest.Inside

class DirectivesTest extends UnitTest with ScalatestRouteTest with Inside with ValidationTestLike {

  import Directives._

  "Directives.extractInstanceId" should {
    "reject and invalid id" in {
      Get("invalid-instance-id") ~> extractInstanceId { instanceId: Instance.Id =>
        complete("")
      } ~> check {
        rejection shouldBe a[ValidationFailed]
        inside(rejection) {
          case ValidationFailed(failure) =>
            failure should haveViolations("/" -> "must fully match regular expression '^(.+)\\.(instance-|marathon-)([^\\.]+)$'")
        }
      }
    }

    "return an id" in {
      Get("valid.instance-uniquekey") ~> extractInstanceId { instanceId: Instance.Id =>
        complete("")
      } ~> check {
        response.status should be(StatusCodes.OK)
      }
    }
  }
}
