package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest

class MultiRoleIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  "MultiRole" should {

    "Marathon should launch tasks as specified role" in {
      Given("a new pod")
      val podInDev = simplePod("/dev/pod-with-role", role = "dev")
      val podInMetrics = simplePod("/metrics/pod-with-role", role = "metrics")

      When("The pods are deployed")
      val resultInDev = marathon.createPodV2(podInDev)
      val resultInMetrics = marathon.createPodV2(podInMetrics)

      Then("The pods are created")
      resultInDev should be(Created)
      resultInMetrics should be(Created)
      //      extractDeploymentIds(resultInDev) should have size 1
      waitForDeployment(resultInDev)
      waitForTasks(podInDev.id, 1) //make sure, the pod has really started

      waitForDeployment(resultInMetrics)
      waitForTasks(podInMetrics.id, 1) //make sure, the pod has really started

    }

  }
}
