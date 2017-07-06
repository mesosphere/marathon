package mesosphere.marathon
package api

import akka.actor.ActorSystem
import ch.qos.logback.classic.{ Level, Logger }
import javax.ws.rs.core.{ MediaType, Request, Variant }
import mesosphere.AkkaUnitTest
import org.slf4j.LoggerFactory
import org.mockito.Matchers
import org.mockito.Mockito.when
import play.api.libs.json.{ JsDefined, JsObject, JsString, Json }

class SystemResourceTest extends AkkaUnitTest {
  class Fixture {
    val auth = new TestAuthFixture
    val conf = mock[MarathonConf]
    val actorSystem = mock[ActorSystem]
    val resource = new SystemResource(conf, system.settings.config)(auth.auth, auth.auth, actorSystem)
  }

  "SystemResource" should {
    "Do a ping" in new Fixture {
      val request = mock[Request]

      When("A ping is requested")
      val response = resource.ping(request)

      Then("A pong is sent back")
      response.getStatus should be(204) // no content
    }

    "Do a ping with preferred JSON content type" in new Fixture {
      val request = mock[Request]
      when(request.selectVariant(Matchers.any())).thenReturn(Variant.mediaTypes(MediaType.APPLICATION_JSON_TYPE).add.build.get(0))

      When("A ping is requested")
      val response = resource.ping(request)

      Then("A pong is sent back")
      val pong = Json.parse(response.getEntity.asInstanceOf[String]).as[JsString]
      pong.value should be("pong")
      Option(response.getMetadata().getFirst("Content-type")).value.toString should be("application/json")
    }

    "Do a ping with text content type" in new Fixture {
      val request = mock[Request]
      when(request.selectVariant(Matchers.any())).thenReturn(Variant.mediaTypes(MediaType.TEXT_PLAIN_TYPE).add.build.get(0))

      When("A ping is requested")
      val response = resource.ping(request)

      Then("A pong is sent back")
      response.getEntity should be("pong")
      Option(response.getMetadata().getFirst("Content-type")).value.toString should be("text/plain")
    }

    "Do a ping with text/* content type" in new Fixture {
      val request = mock[Request]
      when(request.selectVariant(Matchers.any())).thenReturn(Variant.mediaTypes(MediaType.valueOf("text/*")).add.build.get(0))

      When("A ping is requested")
      val response = resource.ping(request)

      Then("A pong is sent back")
      response.getEntity should be("pong")
      Option(response.getMetadata().getFirst("Content-type")).value.toString should be("text/plain")
    }

    "Do a ping with */* content type" in new Fixture {
      val request = mock[Request]
      when(request.selectVariant(Matchers.any())).thenReturn(Variant.mediaTypes(MediaType.valueOf("*/*")).add.build.get(0))

      When("A ping is requested")
      val response = resource.ping(request)

      // JSON is preferred if there's no Accept header, or if Accept is */*
      Then("A pong is sent back")
      val pong = Json.parse(response.getEntity.asInstanceOf[String]).as[JsString]
      pong.value should be("pong")
      Option(response.getMetadata().getFirst("Content-type")).value.toString should be("application/json")
    }

    "Do a ping with text/html content type" in new Fixture {
      val request = mock[Request]
      when(request.selectVariant(Matchers.any())).thenReturn(Variant.mediaTypes(MediaType.TEXT_HTML_TYPE).add.build.get(0))

      When("A ping is requested")
      val response = resource.ping(request)

      Then("A pong is sent back")
      response.getEntity should be("pong")
      Option(response.getMetadata().getFirst("Content-type")).value.toString should be("text/html")
    }

    "Get metrics" in new Fixture {
      When("The metrics are requested")
      val response = resource.metrics(auth.request)

      Then("The metrics are sent")
      val metricsJson = Json.parse(response.getEntity.asInstanceOf[String])
      metricsJson \ "start" shouldBe a[JsDefined]
      metricsJson \ "end" shouldBe a[JsDefined]
      metricsJson \ "counters" shouldBe a[JsDefined]
      metricsJson \ "gauges" shouldBe a[JsDefined]
      metricsJson \ "histograms" shouldBe a[JsDefined]
    }

    "access without authentication is denied" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false

      When("we try to fetch metrics")
      val fetchedMetrics = resource.metrics(auth.request)
      Then("we receive a NotAuthenticated response")
      fetchedMetrics.getStatus should be(auth.NotAuthenticatedStatus)

      When("we try to get logging")
      val showLoggers = resource.showLoggers(auth.request)
      Then("we receive a NotAuthenticated response")
      showLoggers.getStatus should be(auth.NotAuthenticatedStatus)

      When("we try to change loggers")
      val changeLogger = resource.changeLogger("""{ "level": "debug", "logger": "org" }""".getBytes, auth.request)
      Then("we receive a NotAuthenticated response")
      showLoggers.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "access without authorization is denied" in new Fixture {
      Given("An unauthorized request")
      auth.authorized = false

      When("we try to fetch metrics")
      val fetchedMetrics = resource.metrics(auth.request)
      Then("we receive a Unauthorized response")
      fetchedMetrics.getStatus should be(auth.UnauthorizedStatus)

      When("we try to get logging")
      val showLoggers = resource.showLoggers(auth.request)
      Then("we receive a Unauthorized response")
      showLoggers.getStatus should be(auth.UnauthorizedStatus)

      When("we try to change loggers")
      val changeLogger = resource.changeLogger("""{ "level": "debug", "logger": "org" }""".getBytes, auth.request)
      Then("we receive a Unauthorized response")
      showLoggers.getStatus should be(auth.UnauthorizedStatus)
    }

    "show all loggers will give a map of all loggers with level" in new Fixture {
      val showLoggers = resource.showLoggers(auth.request)
      showLoggers.getStatus should be (200)
      val loggerMap = Json.parse(showLoggers.getEntity.asInstanceOf[String]).as[JsObject]
      loggerMap.values should not be empty
      loggerMap.keys should contain ("mesosphere.marathon")
      loggerMap.keys should contain ("ROOT")
    }

    "change a logger via the api will update the log lebel" in new Fixture {
      When("We set the log level of not.used to trace")
      resource.changeLogger("""{ "level": "trace", "logger": "not.used" }""".getBytes, auth.request)
      Then("The log level is set to trace")
      LoggerFactory.getILoggerFactory.getLogger("not.used").asInstanceOf[Logger].getLevel should be (Level.TRACE)
    }
  }
}
