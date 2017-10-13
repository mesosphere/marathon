package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.qos.logback.classic.{Level, Logger}
import com.typesafe.config.Config
import mesosphere.UnitTest
import mesosphere.marathon.api.{JsonTestHelper, TestAuthFixture}
import mesosphere.marathon.api.akkahttp.v2.RouteBehaviours
import mesosphere.marathon.core.election.ElectionService
import org.scalatest.prop.TableDrivenPropertyChecks
import org.slf4j.LoggerFactory


class SystemControllerTest extends UnitTest with ScalatestRouteTest with RouteBehaviours with TableDrivenPropertyChecks {

  "SystemController" should {
    // format: OFF
    val pingCases = Table[MediaRange, ContentType, StatusCode](
      ("AcceptMediaType",       "ResponseContentType",                "StatusCode"         ),
      (MediaRange(MediaTypes.`application/json`), ContentTypes.`application/json`, StatusCodes.OK),
      (MediaRanges.`text/*`, ContentTypes.`text/plain(UTF-8)`, StatusCodes.OK),
      (MediaRange(MediaTypes.`text/plain`), ContentTypes.`text/plain(UTF-8)`, StatusCodes.OK),
      (MediaRanges.`*/*`, ContentTypes.`text/plain(UTF-8)`, StatusCodes.OK), // TODO: Maybe set none at all
      (MediaRange(MediaTypes.`image/png`), ContentTypes.NoContentType, StatusCodes.NoContent)
    )
    // format: ON
    forAll(pingCases) { (acceptMediaType, responseContentType, statusCode) =>
      s"reply to a ping request ${acceptMediaType} with a pong of ${responseContentType}" in {
        val controller = Fixture().controller()
        val acceptHeader = Accept(acceptMediaType)
        Get("/ping").addHeader(acceptHeader) ~> controller.route ~> check {
          response.status should be(statusCode)
          response.entity.contentType should be(responseContentType)
        }
      }
    }
//    "Do a ping with preferred JSON content type" in new Fixture {
//      val request = mock[Request]
//      when(request.selectVariant(Matchers.any())).thenReturn(Variant.mediaTypes(MediaType.APPLICATION_JSON_TYPE).add.build.get(0))
//
//      When("A ping is requested")
//      val response = resource.ping(request)
//
//      Then("A pong is sent back")
//      val pong = Json.parse(response.getEntity.asInstanceOf[String]).as[JsString]
//      pong.value should be("pong")
//      Option(response.getMetadata().getFirst("Content-type")).value.toString should be("application/json")
//    }

//    "Do a ping with text content type" in new Fixture {
//      val request = mock[Request]
//      when(request.selectVariant(Matchers.any())).thenReturn(Variant.mediaTypes(MediaType.TEXT_PLAIN_TYPE).add.build.get(0))
//
//      When("A ping is requested")
//      val response = resource.ping(request)
//
//      Then("A pong is sent back")
//      response.getEntity should be("pong")
//      Option(response.getMetadata().getFirst("Content-type")).value.toString should be("text/plain")
//    }

//    "Do a ping with text/* content type" in new Fixture {
//      val request = mock[Request]
//      when(request.selectVariant(Matchers.any())).thenReturn(Variant.mediaTypes(MediaType.valueOf("text/*")).add.build.get(0))
//
//      When("A ping is requested")
//      val response = resource.ping(request)
//
//      Then("A pong is sent back")
//      response.getEntity should be("pong")
//      Option(response.getMetadata().getFirst("Content-type")).value.toString should be("text/plain")
//    }

//    "Do a ping with */* content type" in new Fixture {
//      val request = mock[Request]
//      when(request.selectVariant(Matchers.any())).thenReturn(Variant.mediaTypes(MediaType.valueOf("*/*")).add.build.get(0))
//
//      When("A ping is requested")
//      val response = resource.ping(request)
//
//      // JSON is preferred if there's no Accept header, or if Accept is */*
//      Then("A pong is sent back")
//      val pong = Json.parse(response.getEntity.asInstanceOf[String]).as[JsString]
//      pong.value should be("pong")
//      Option(response.getMetadata().getFirst("Content-type")).value.toString should be("application/json")
//    }

//    "Do a ping with text/html content type" in new Fixture {
//      val request = mock[Request]
//      when(request.selectVariant(Matchers.any())).thenReturn(Variant.mediaTypes(MediaType.TEXT_HTML_TYPE).add.build.get(0))
//
//      When("A ping is requested")
//      val response = resource.ping(request)
//
//      Then("A pong is sent back")
//      response.getEntity should be("pong")
//      Option(response.getMetadata().getFirst("Content-type")).value.toString should be("text/html")
//    }

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
