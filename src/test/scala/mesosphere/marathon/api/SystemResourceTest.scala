package mesosphere.marathon
package api

import com.codahale.metrics.MetricRegistry
import mesosphere.UnitTest
import play.api.libs.json.{ JsDefined, Json }

class SystemResourceTest extends UnitTest {
  class Fixture {
    val auth = new TestAuthFixture
    val metrics = new MetricRegistry
    val conf = mock[MarathonConf]
    val resource = new SystemResource(metrics, conf)(auth.auth, auth.auth)
  }

  "SystemResource" should {
    "Do a ping" in new Fixture {
      When("A ping is requested")
      val response = resource.ping()

      Then("A pong is send back")
      response.getEntity should be("pong")
    }

    "Get metrics" in new Fixture {
      When("The metrics are requested")
      val response = resource.metrics(auth.request)

      Then("The metrics are send")
      val metricsJson = Json.parse(response.getEntity.asInstanceOf[String])
      metricsJson \ "counters" shouldBe a[JsDefined]
      metricsJson \ "gauges" shouldBe a[JsDefined]
      metricsJson \ "meters" shouldBe a[JsDefined]
      metricsJson \ "timers" shouldBe a[JsDefined]
    }

    "access without authentication is denied" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false

      When("we try to fetch metrics")
      val fetchedMetrics = resource.metrics(auth.request)
      Then("we receive a NotAuthenticated response")
      fetchedMetrics.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "access without authorization is denied" in new Fixture {
      Given("An unauthorized request")
      auth.authorized = false

      When("we try to fetch metrics")
      val fetchedMetrics = resource.metrics(auth.request)
      Then("we receive a NotAuthenticated response")
      fetchedMetrics.getStatus should be(auth.UnauthorizedStatus)
    }
  }
}
