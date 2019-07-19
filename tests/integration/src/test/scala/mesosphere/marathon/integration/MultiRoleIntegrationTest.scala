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
      Given("an app in role dev")
      val appInDev = appProxy(PathId("/dev/app-with-role"), "v1", instances = 1, role = Some("dev"))

      When("The app is deployed")
      val resultInDev = marathon.createAppV2(appInDev)

      Then("The apps are created")
      resultInDev should be(Created)
      waitForDeployment(resultInDev)
      waitForTasks(PathId(appInDev.id), 1) //make sure, the pod has really started

      Given("an app in role metrics")
      val appInMetrics = appProxy(PathId("/metrics/app-with-role"), "v1", instances = 1, role = Some("metrics"))

      When("The app is deployed")
      val resultInMetrics = marathon.createAppV2(appInMetrics)

      Then("The apps are created")
      resultInMetrics should be(Created)
      waitForDeployment(resultInMetrics)
      waitForTasks(PathId(appInMetrics.id), 1) //make sure, the pod has really started

    }
  }

  "Marathon should launch an resident app as non-default role" in {
    Given("an app in role dev")
    val appInDev = residentApp(PathId("/dev/simple-resident-app-with-role"), role = Some("dev"))

    When("The app is deployed")
    val resultInDev = marathon.createAppV2(appInDev)

    Then("The apps are created")
    resultInDev should be(Created)
    waitForDeployment(resultInDev)
    waitForTasks(PathId(appInDev.id), 1) //make sure the app has really started
  }
}
