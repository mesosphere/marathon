package mesosphere.marathon
package integration

import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.state.PortDefinition

import scala.collection.immutable
import scala.concurrent.duration._

@IntegrationTest
class HealthDeploymentLeaderAbdicationIntegrationTest extends DeploymentLeaderAbdicationIntegrationTest {

  override val numAdditionalMarathons = 1

  after(cleanUp())

  "HealthDeploymentLeaderAbdication" should {
    "deployment with 1 healthy and 1 unhealthy instance is continued properly after master abdication" in {
      val appId = testBasePath / "app"
      val create = appProxy(appId, versionId = "v1", instances = 2, healthCheck = None)

      val plan = "phase(block1)"
      val update = AppUpdate(
        cmd = Some(s"""$serviceMockScript '$plan'"""),
        portDefinitions = Some(immutable.Seq(PortDefinition(0, name = Some("http")))),
        healthChecks = Some(Set(healthCheck)))

      test(appId, create, update)
    }
  }
  private lazy val healthCheck: MarathonHttpHealthCheck = MarathonHttpHealthCheck(
    path = Some("/v1/plan"),
    portIndex = Some(PortReference(0)),
    interval = 2.seconds,
    timeout = 1.second)
}
