package mesosphere.marathon.integration

import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.integration.setup.{ IntegrationFunSuite, ProcessKeeper, WaitTestSupport, WithMesosCluster }
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }

import scala.concurrent.duration._

/**
  * Regression Test for https://github.com/mesosphere/marathon/issues/3783
  *
  * Adapted from https://github.com/EvanKrall/reproduce_marathon_issue_3783
  */
class LeadershipAbdicationRegressionTest extends IntegrationFunSuite with WithMesosCluster with Matchers with GivenWhenThen with BeforeAndAfter {

  before {
    cleanUp()
    if (!ProcessKeeper.hasProcess(master1)) startMaster(master1)
    if (!ProcessKeeper.hasProcess(slave1)) startSlave(slave1)
  }

  test("Abdicating a leader does not kill a running task") {
    Given("a new app with an impossible constraint")
    // Running locally, the constraint of a unique hostname should prevent the second instance from deploying.
    val constraint = Protos.Constraint.newBuilder()
      .setField("hostname")
      .setOperator(Operator.UNIQUE)
      .build()
    val app = appProxy(testBasePath / "app", "v1", instances = 2)
      .copy(constraints = Set(constraint))
    marathon.createAppV2(app)

    When("one of the tasks is deployed")
    val tasksBeforeAbdication = waitForTasks(app.id, 1)

    And("and the leader abdicates")
    val firstResult = marathon.abdicate()
    firstResult.code should be (200)
    (firstResult.entityJson \ "message").as[String] should be ("Leadership abdicated")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }
    val tasksAfterFirstAbdication = waitForTasks(app.id, 1)

    Then("the task should not be killed")
    tasksBeforeAbdication should be (tasksAfterFirstAbdication)

    When("the leader abdicates")
    val secondResult = marathon.abdicate()
    secondResult.code should be (200)
    (secondResult.entityJson \ "message").as[String] should be ("Leadership abdicated")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }
    val tasksAfterSecondAbdication = waitForTasks(app.id, 1)

    Then("the task should not be killed")
    tasksBeforeAbdication should be (tasksAfterSecondAbdication)

    When("the leader abdicates")
    val thirdResult = marathon.abdicate()
    thirdResult.code should be (200)
    (thirdResult.entityJson \ "message").as[String] should be ("Leadership abdicated")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }
    val tasksAfterThirdAbdication = waitForTasks(app.id, 1)

    Then("the task should not be killed")
    tasksBeforeAbdication should be (tasksAfterThirdAbdication)

    When("the leader abdicates")
    val fourthResult = marathon.abdicate()
    fourthResult.code should be (200)
    (fourthResult.entityJson \ "message").as[String] should be ("Leadership abdicated")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }
    val tasksAfterFourthAbdication = waitForTasks(app.id, 1)

    Then("the task should not be killed")
    tasksBeforeAbdication should be (tasksAfterFourthAbdication)

    When("the leader abdicates")
    val fifthResult = marathon.abdicate()
    fifthResult.code should be (200)
    (fifthResult.entityJson \ "message").as[String] should be ("Leadership abdicated")
    WaitTestSupport.waitUntil("a leader has been elected", 30.seconds) { marathon.leader().code == 200 }
    val tasksAfterFifthAbdication = waitForTasks(app.id, 1)

    Then("the task should not be killed")
    tasksBeforeAbdication should be (tasksAfterFifthAbdication)
  }

}
