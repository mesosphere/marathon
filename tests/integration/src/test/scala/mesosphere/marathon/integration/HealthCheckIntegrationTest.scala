package mesosphere.marathon
package integration

import java.util.UUID

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest
import mesosphere.marathon.raml.{AppHealthCheck, AppHealthCheckProtocol}
import mesosphere.marathon.state.PathId

import scala.concurrent.duration._

class HealthCheckIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  def appId(suffix: Option[String] = None): PathId = testBasePath / s"app-${suffix.getOrElse(UUID.randomUUID)}"

  "Health checks" should {
    "replace an unhealthy app" in {
      Given("a deployed app with health checks")
      val app = appProxy(appId(Some("replace-marathon-http-health-check")), "v1", instances = 1, healthCheck = None).
        copy(healthChecks = Set(ramlHealthCheck))
      val check = registerAppProxyHealthCheck(PathId(app.id), "v1", state = true)
      val result = marathon.createAppV2(app)
      result should be(Created)
      waitForDeployment(result)

      When("the app becomes unhealthy")
      check.afterDelay(1.seconds, false)

      Then("the unhealthy instance is killed")
      waitForEvent("unhealthy_instance_kill_event")
    }
  }

  private val ramlHealthCheck = AppHealthCheck(
    path = Some("/health"),
    protocol = AppHealthCheckProtocol.Http,
    gracePeriodSeconds = 20,
    intervalSeconds = 1,
    maxConsecutiveFailures = 5,
    portIndex = Some(0),
    delaySeconds = 2
  )
}
