package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.model.{ HttpRequest, StatusCodes }
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.marathon.api.akkahttp.AuthDirectives.{ NotAuthenticated, NotAuthorized }
import mesosphere.marathon.api.akkahttp.Rejections.{ EntityNotFound, Message }
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
      When(s"we try to fetch from $forRoute")
      withRequest ~> forRoute ~> check {
        Then("we receive a NotAuthenticated response")
        rejections.find{ case _: NotAuthorized => true } should not be 'empty
        rejections.collect {
          case NotAuthorized(response) =>
            response.status should be(StatusCodes.Unauthorized)
        }
      }
    }
  }

  def unknownEntity(forRoute: Route, withRequest: HttpRequest, withMessage: String): Unit = {
    s"reject ${withRequest.method.value} of ${withRequest.uri} for entity not found: $withMessage" in {
      When(s"we try to fetch from $forRoute")
      withRequest ~> forRoute ~> check {
        Then(s"we receive a entity not found: $withMessage")
        rejection should be(EntityNotFound(Message(withMessage)))
      }
    }
  }

}
