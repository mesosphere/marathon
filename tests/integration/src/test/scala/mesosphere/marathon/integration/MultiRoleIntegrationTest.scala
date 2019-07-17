package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup.{EmbeddedMarathonTest, MesosConfig}
import mesosphere.marathon.state.PathId

class MultiRoleIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  override lazy val mesosConfig = MesosConfig(
    restrictedToRoles = None
  )

  "MultiRole" should {

    "Marathon should launch tasks as specified role" in {
      Given("a new pod")
      val appInDev = appProxy(PathId("/dev/app-with-role"), "v1", instances = 1, role = Some("dev"))
      val appInMetrics = appProxy(PathId("/metrics/app-with-role"), "v1", instances = 1, role = Some("metrics"))

      When("The apps are deployed")
      val resultInDev = marathon.createAppV2(appInDev)
      val resultInMetrics = marathon.createAppV2(appInMetrics)

      Then("The apps are created")
      resultInDev should be(Created)
      resultInMetrics should be(Created)

      waitForDeployment(resultInDev)
      waitForTasks(PathId(appInDev.id), 1) //make sure, the pod has really started

      waitForDeployment(resultInMetrics)
      waitForTasks(PathId(appInMetrics.id), 1) //make sure, the pod has really started

    }

  }
}
