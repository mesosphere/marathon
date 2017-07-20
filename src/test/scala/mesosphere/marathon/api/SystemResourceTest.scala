package mesosphere.marathon.api

import com.codahale.metrics.MetricRegistry
import mesosphere.FunTest
import mesosphere.marathon.MarathonConf
import play.api.libs.json.{ JsDefined, Json, JsString }
import javax.ws.rs.core.{ MediaType, Request, Variant }
import org.mockito.Matchers
import org.mockito.Mockito.when

class SystemResourceTest extends FunTest {

  test("Do a ping no response") {
    Given("The system resource")
    val f = new Fixture
    import f._
    val request = mock[Request]

    When("A ping is requested")
    val response = resource.ping(request)

    Then("A pong is send back")
    response.getStatus should be(204)
  }

  test("Do a ping for json") {
    Given("The system resource")
    val f = new Fixture
    import f._
    val request = mock[Request]
    when(request.selectVariant(Matchers.any())).thenReturn(Variant.mediaTypes(MediaType.APPLICATION_JSON_TYPE).add.build.get(0))

    When("A ping is requested")
    val response = resource.ping(request)

    Then("A pong is send back")
    val pong = Json.parse(response.getEntity.asInstanceOf[String]).as[JsString]
    pong.value should be("pong")
    Option(response.getMetadata().getFirst("Content-type")).value.toString should be("application/json")
  }

  test("Do a ping for text content type") {
    Given("The system resource")
    val f = new Fixture
    import f._
    val request = mock[Request]
    when(request.selectVariant(Matchers.any())).thenReturn(Variant.mediaTypes(MediaType.TEXT_PLAIN_TYPE).add.build.get(0))

    When("A ping is requested")
    val response = resource.ping(request)

    Then("A pong is send back")
    response.getEntity should be("pong")
    Option(response.getMetadata().getFirst("Content-type")).value.toString should be("text/plain")
  }

  test("Get metrics") {
    Given("The system resource")
    val f = new Fixture
    import f._

    When("The metrics are requested")
    val response = resource.metrics(auth.request)

    Then("The metrics are send")
    val metricsJson = Json.parse(response.getEntity.asInstanceOf[String])
    metricsJson \ "counters" shouldBe a[JsDefined]
    metricsJson \ "gauges" shouldBe a[JsDefined]
    metricsJson \ "meters" shouldBe a[JsDefined]
    metricsJson \ "timers" shouldBe a[JsDefined]
  }

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    val f = new Fixture
    import f._
    auth.authenticated = false

    When("we try to fetch metrics")
    val metrics = resource.metrics(auth.request)
    Then("we receive a NotAuthenticated response")
    metrics.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied") {
    Given("An unauthorized request")
    val f = new Fixture
    import f._
    auth.authorized = false

    When("we try to fetch metrics")
    val metrics = resource.metrics(auth.request)
    Then("we receive a NotAuthenticated response")
    metrics.getStatus should be(auth.UnauthorizedStatus)
  }

  class Fixture {
    val auth = new TestAuthFixture
    val metrics = new MetricRegistry
    val conf = mock[MarathonConf]
    val resource = new SystemResource(metrics, conf)(auth.auth, auth.auth)
  }

}
