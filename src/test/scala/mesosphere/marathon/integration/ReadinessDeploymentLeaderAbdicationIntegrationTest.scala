package mesosphere.marathon
package integration

import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.state.PortDefinition

import scala.collection.immutable
import scala.concurrent.duration._

@IntegrationTest
class ReadinessDeploymentLeaderAbdicationIntegrationTest extends DeploymentLeaderAbdicationIntegrationTest {

  override val numAdditionalMarathons = 1

  after(cleanUp())

  "ReadinessDeploymentLeaderAbdication" should {
    "deployment with 1 ready and 1 not ready instance is continued properly after master abdication" in {
      val appId = testBasePath / "app"
      val create = appProxy(appId, versionId = "v1", instances = 2, healthCheck = None)

      val plan = "phase(block1)"
      val update = AppUpdate(
        cmd = Some(s"""$serviceMockScript '$plan'"""),
        portDefinitions = Some(immutable.Seq(PortDefinition(0, name = Some("http")))),
        readinessChecks = Some(Seq(readinessCheck)))

      test(appId, create, update)
    }
  }
  private lazy val readinessCheck = ReadinessCheck(
    "ready",
    portName = "http",
    path = "/v1/plan",
    interval = 2.seconds,
    timeout = 1.second,
    preserveLastResponse = true)
}
