package mesosphere.marathon.api

import com.codahale.metrics.MetricRegistry
import mesosphere.FunTest
import mesosphere.marathon.MarathonConf
import play.api.libs.json.{ JsDefined, Json }

class SystemResourceTest extends FunTest {

  test("Do a ping") {
    Given("The system resource")
    val f = new Fixture
    import f._

    When("A ping is requested")
    val response = resource.ping()

    Then("A pong is send back")
    response.getEntity should be("pong")
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
