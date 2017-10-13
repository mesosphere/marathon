package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.config.Config
import mesosphere.UnitTest
import mesosphere.marathon.api.{JsonTestHelper, TestAuthFixture}
import mesosphere.marathon.api.akkahttp.v2.RouteBehaviours
import mesosphere.marathon.core.election.ElectionService


class SystemControllerTest extends UnitTest with ScalatestRouteTest with RouteBehaviours {

  "SystemController" should {
    "return a ping" in {
      val controller = Fixture().controller()
      val entity = HttpEntity.Empty.withContentType(ContentTypes.`application/json`)
      Get("/ping", entity) ~> controller.route ~> check {
        response.status should be(StatusCodes.NoContent)
      }
    }

    "return a snapshot of the metrics" in {
      val controller = Fixture().controller()
      Get("/metrics") ~> controller.route ~> check {
        response.status should be(StatusCodes.OK)
        val expected =
          """{
            |  "start" : "2017-10-13T09:50:55.707+02:00",
            |  "end" : "2017-10-13T09:50:55.707+02:00",
            |  "histograms" : { },
            |  "counters" : { },
            |  "gauges" : { },
            |  "min-max-counters" : { }
            |}""".stripMargin
        JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
      }
    }

    "return all loggers" in {
      val controller = Fixture().controller()
      Get("/logging") ~> controller.route ~> check {
        response.status should be(StatusCodes.OK)
        val expected =
          """{
            |
            |}""".stripMargin
        JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
      }
    }


    {
      val controller = Fixture(authenticated = false).controller()
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get("/config"))
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get("/logging"))
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get("/metrics"))
    }

    {
      val controller = Fixture(authorized = false).controller()
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = Get("/config"))
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = Get("/logging"))
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = Get("/metrics"))
    }
  }

  case class Fixture(authenticated: Boolean = true, authorized: Boolean = true) {
    val auth = new TestAuthFixture()
    auth.authenticated = authenticated
    auth.authorized = authorized
    implicit val authenticator = auth.auth

    val marathonConfig = mock[MarathonConf]
    val conf = mock[Config]

    val electionService = mock[ElectionService]
    electionService.isLeader returns true

    def controller() = new SystemController(marathonConfig, conf, electionService)
  }

}
