package mesosphere.marathon
package integration

import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.integration.setup.WaitTestSupport

import scala.concurrent.duration._

/**
  * Regression Test for https://github.com/mesosphere/marathon/issues/3783
  *
  * Intention:
  * During an abdication, there should be a deployment in progress, containing at
  * least one running task. This running task should not be killed/replaced during
  * the next leader takes over the deployment.
  *
  * Adapted from https://github.com/EvanKrall/reproduce_marathon_issue_3783
  */
@IntegrationTest
class LeadershipAbdicationIntegrationTest extends LeaderIntegrationTest {

  // to simulate multiple leader abdicates, we do two loops
  val abdicationLoops = 2

  // we need the same amount of additional marathon instances, like we abdicate afterwards.
  override val numAdditionalMarathons = abdicationLoops

  override lazy val mesosNumSlaves = 1

  test("Abdicating a leader does not kill a running task which is currently involved in a deployment") {
    Given("a new app with an impossible constraint")
    // Running locally, the constraint of a unique hostname should prevent the second instance from deploying.
    val constraint = Protos.Constraint.newBuilder()
      .setField("hostname")
      .setOperator(Operator.UNIQUE)
      .build()
    val app = appProxy(testBasePath / "app3783", "v2", instances = 2, healthCheck = None)
      .copy(constraints = Set(constraint))
    marathon.createAppV2(app)

    When("one of the tasks is deployed")
    val tasksBeforeAbdication = waitForTasks(app.id, 1)

    (1 to abdicationLoops).foreach {
      _ =>
        And("the leader abdicates")
        val result = firstRunningProcess.client.abdicate()
        result.code should be (200)
        (result.entityJson \ "message").as[String] should be ("Leadership abdicated")
        WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { firstRunningProcess.client.leader().code == 200 }
        val tasksAfterFirstAbdication = waitForTasks(app.id, 1)(firstRunningProcess.client)

        Then("the already running task should not be killed")
        tasksBeforeAbdication should be (tasksAfterFirstAbdication)
    }
  }

}
