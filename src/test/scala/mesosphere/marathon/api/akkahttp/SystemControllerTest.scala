package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.qos.logback.classic.{ Level, Logger }
import com.typesafe.config.Config
import mesosphere.UnitTest
import mesosphere.marathon.api.{ JsonTestHelper, TestAuthFixture }
import mesosphere.marathon.api.akkahttp.v2.RouteBehaviours
import mesosphere.marathon.core.election.ElectionService
import org.scalatest.prop.TableDrivenPropertyChecks
import org.slf4j.LoggerFactory

class SystemControllerTest extends UnitTest with ScalatestRouteTest with RouteBehaviours with TableDrivenPropertyChecks {

  "SystemController" should {
    // format: OFF
    val pingCases = Table[Option[MediaRange], ContentType, StatusCode](
      ("AcceptMediaType",                               "ResponseContentType",            "StatusCode"),
      (Some(MediaRange(MediaTypes.`application/json`)), ContentTypes.`application/json`,  StatusCodes.OK),
      (Some(MediaRanges.`text/*`),                      ContentTypes.`text/plain(UTF-8)`, StatusCodes.OK),
      (Some(MediaRange(MediaTypes.`text/plain`)),       ContentTypes.`text/plain(UTF-8)`, StatusCodes.OK),
      (Some(MediaRanges.`*/*`),                         ContentTypes.`text/plain(UTF-8)`, StatusCodes.OK),
      (None,                                            ContentTypes.`text/plain(UTF-8)`, StatusCodes.OK),
      (Some(MediaRange(MediaTypes.`image/png`)),        ContentTypes.NoContentType,       StatusCodes.NoContent)
    )
    // format: ON
    forAll(pingCases) { (acceptMediaType, responseContentType, statusCode) =>
      s"reply to a ping request ${acceptMediaType} with a pong of ${responseContentType}" in {
        val controller = Fixture().controller()

        Given(s"a request with accept header for $acceptMediaType")
        val request = acceptMediaType match {
          case None => Get("/ping")
          case Some(mediaType) => Get("/ping").addHeader(Accept(mediaType))
        }

        When("the request is issued")
        request ~> controller.route ~> check {
          Then(s"the response has status code $statusCode")
          response.status should be(statusCode)
          response.entity.contentType should be(responseContentType)
        }
      }
    }

    "return a snapshot of the metrics" in {
      val controller = Fixture().controller()
      Get("/metrics") ~> controller.route ~> check {
        response.status should be(StatusCodes.OK)
      }
    }

    "return all loggers" in {
      val controller = Fixture().controller()
      Get("/logging") ~> controller.route ~> check {
        response.status should be(StatusCodes.OK)
        val expected =
          """{
            |  "mesosphere.marathon.api.akkahttp.SystemControllerTest" : "INFO (inherited)",
            |  "akka.stream" : "INFO (inherited)",
            |  "mesosphere.marathon" : "INFO",
            |  "org.eclipse" : "INFO",
            |  "akka.event.EventStreamUnsubscriber" : "INFO (inherited)",
            |  "org.apache.zookeeper" : "WARN",
            |  "akka.actor.LocalActorRefProvider" : "INFO (inherited)",
            |  "akka" : "INFO",
            |  "mesosphere.marathon.api.akkahttp.SystemController" : "INFO (inherited)",
            |  "org.apache" : "INFO (inherited)",
            |  "mesosphere.marathon.api.akkahttp.Directives" : "INFO (inherited)",
            |  "akka.actor.LocalActorRefProvider$Guardian" : "INFO (inherited)",
            |  "akka.actor" : "INFO (inherited)",
            |  "akka.event.slf4j" : "INFO (inherited)",
            |  "mesosphere.marathon.api.akkahttp.Directives$" : "INFO (inherited)",
            |  "akka.event.slf4j.Slf4jLogger" : "INFO (inherited)",
            |  "akka.stream.impl.StreamSupervisor" : "INFO (inherited)",
            |  "mesosphere.marathon.api" : "INFO (inherited)",
            |  "akka.event" : "INFO (inherited)",
            |  "spray" : "ERROR",
            |  "akka.stream.impl" : "INFO (inherited)",
            |  "native-zk-connector" : "WARN",
            |  "org" : "INFO (inherited)",
            |  "mesosphere" : "INFO (inherited)",
            |  "mesosphere.marathon.api.akkahttp" : "INFO (inherited)",
            |  "mesosphere.marathon.integration.process" : "DEBUG",
            |  "mesosphere.marathon.integration" : "INFO (inherited)",
            |  "ROOT" : "INFO",
            |  "akka.event.EventStream" : "INFO (inherited)",
            |  "akka.event.DeadLetterListener" : "INFO (inherited)",
            |  "akka.actor.LocalActorRefProvider$SystemGuardian" : "INFO (inherited)"
            |}""".stripMargin
        JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
      }
    }

    "change the log level" in {
      Given("A request to change the log level")
      val controller = Fixture().controller()
      val body =
        """{
          | "level": "trace",
          | "logger": "not.used"
          |}""".stripMargin
      val entity = HttpEntity(body).withContentType(ContentTypes.`application/json`)

      When("The request is posted to the logging endpoint")
      Post("/logging", entity) ~> controller.route ~> check {
        response.status should be(StatusCodes.OK)

        Then("The log level is set to trace")
        LoggerFactory.getILoggerFactory.getLogger("not.used").asInstanceOf[Logger].getLevel should be (Level.TRACE)
      }
    }

    {
      val controller = Fixture(authenticated = false).controller()
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get("/config"))
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get("/logging"))
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Post("/logging"))
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get("/metrics"))
    }

    {
      val controller = Fixture(authorized = false).controller()
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = Get("/config"))
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = Get("/logging"))
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = Post("/logging"))
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
