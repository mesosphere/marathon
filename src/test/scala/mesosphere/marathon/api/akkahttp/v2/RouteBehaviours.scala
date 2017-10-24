package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.model.{ HttpRequest, StatusCodes, Uri }
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.marathon.api.akkahttp.AuthDirectives.{ NotAuthenticated, NotAuthorized }
import org.scalatest.Inside

trait RouteBehaviours extends ScalatestRouteTest with Inside { this: UnitTest =>

  def unauthenticatedRoute(forRoute: Route, withRequest: HttpRequest, customText: String = ""): Unit = {
    s"deny access to ${withRequest.method.value} of ${withRequest.uri} without authentication $customText" in {
      When("we try to fetch the info")
      withRequest ~> forRoute ~> check {
        Then("we receive a NotAuthenticated response")
        rejection shouldBe a[NotAuthenticated]
        inside(rejection) {
          case NotAuthenticated(response) =>
            response.status should be(StatusCodes.Forbidden)
        }
      }
    }
  }

  def unauthorizedRoute(forRoute: Route, withRequest: HttpRequest): Unit = {
    s"deny access to ${withRequest.method.value} of ${withRequest.uri} without authorization" in {
      When("we try to fetch the info")
      withRequest ~> forRoute ~> check {
        Then("we receive a NotAuthenticated response")
        rejection shouldBe a[NotAuthorized]
        inside(rejection) {
          case NotAuthorized(response) =>
            response.status should be(StatusCodes.Unauthorized)
        }
      }
    }
  }

}
