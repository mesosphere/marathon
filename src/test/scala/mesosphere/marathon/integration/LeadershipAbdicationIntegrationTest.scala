package mesosphere.marathon
package integration

import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.integration.setup.WaitTestSupport

import scala.concurrent.duration._

/**
  * Regression Test for https://github.com/mesosphere/marathon/issues/3783
  *
  * Adapted from https://github.com/EvanKrall/reproduce_marathon_issue_3783
  */
@IntegrationTest
class LeadershipAbdicationIntegrationTest extends LeaderIntegrationTest {

  after(cleanUp())

  override val numAdditionalMarathons = 5

  def firstProcess = runningServerProcesses.headOption.getOrElse(
    fail("there are marathon servers running")
  )

  test("Abdicating a leader does not kill a running task") {
    Given("a new app with an impossible constraint")
    // Running locally, the constraint of a unique hostname should prevent the second instance from deploying.
    val constraint = Protos.Constraint.newBuilder()
      .setField("hostname")
      .setOperator(Operator.UNIQUE)
      .build()
    val app = appProxy(testBasePath / "app3783", "v2", instances = 2)
      .copy(constraints = Set(constraint))
    marathon.createAppV2(app)

    When("one of the tasks is deployed")
    val tasksBeforeAbdication = waitForTasks(app.id, 1)

    And("and the leader abdicates")
    val firstResult = marathon.abdicate()
    firstResult.code should be (200)
    (firstResult.entityJson \ "message").as[String] should be ("Leadership abdicated")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { firstProcess.client.leader().code == 200 }
    val tasksAfterFirstAbdication = waitForTasks(app.id, 1)(firstProcess.client)

    Then("the task should not be killed")
    tasksBeforeAbdication should be (tasksAfterFirstAbdication)

    When("the leader abdicates")
    val secondResult = firstProcess.client.abdicate()
    secondResult.code should be (200)
    (secondResult.entityJson \ "message").as[String] should be ("Leadership abdicated")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { firstProcess.client.leader().code == 200 }
    val tasksAfterSecondAbdication = waitForTasks(app.id, 1)(firstProcess.client)

    Then("the task should not be killed")
    tasksBeforeAbdication should be (tasksAfterSecondAbdication)

    When("the leader abdicates")
    val thirdResult = firstProcess.client.abdicate()
    thirdResult.code should be (200)
    (thirdResult.entityJson \ "message").as[String] should be ("Leadership abdicated")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { firstProcess.client.leader().code == 200 }
    val tasksAfterThirdAbdication = waitForTasks(app.id, 1)(firstProcess.client)

    Then("the task should not be killed")
    tasksBeforeAbdication should be (tasksAfterThirdAbdication)

    When("the leader abdicates")
    val fourthResult = firstProcess.client.abdicate()
    fourthResult.code should be (200)
    (fourthResult.entityJson \ "message").as[String] should be ("Leadership abdicated")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { firstProcess.client.leader().code == 200 }
    val tasksAfterFourthAbdication = waitForTasks(app.id, 1)(firstProcess.client)

    Then("the task should not be killed")
    tasksBeforeAbdication should be (tasksAfterFourthAbdication)

    When("the leader abdicates")
    val fifthResult = firstProcess.client.abdicate()
    fifthResult.code should be (200)
    (fifthResult.entityJson \ "message").as[String] should be ("Leadership abdicated")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { firstProcess.client.leader().code == 200 }
    val tasksAfterFifthAbdication = waitForTasks(app.id, 1)(firstProcess.client)

    Then("the task should not be killed")
    tasksBeforeAbdication should be (tasksAfterFifthAbdication)
  }

}
