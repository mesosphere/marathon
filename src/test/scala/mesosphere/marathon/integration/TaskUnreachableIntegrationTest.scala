package mesosphere.marathon.integration

import mesosphere.marathon.integration.facades.ITEnrichedTask
import mesosphere.marathon.integration.setup._
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }
import org.slf4j.LoggerFactory

class TaskUnreachableIntegrationTest extends IntegrationFunSuite
    with WithMesosCluster
    with Matchers
    with GivenWhenThen
    with BeforeAndAfter {

  private val log = LoggerFactory.getLogger(getClass)

  //override to start marathon with a low reconciliation frequency
  override def startMarathon(port: Int, ignore: String*): Unit = {
    val args = List(
      "--master", config.master,
      "--event_subscriber", "http_callback",
      "--access_control_allow_origin", "*",
      "--reconciliation_initial_delay", "5000",
      "--reconciliation_interval", "5000",
      "--scale_apps_initial_delay", "5000",
      "--scale_apps_interval", "5000",
      "--min_revive_offers_interval", "100",
      "--task_lost_expunge_gc", "30000",
      "--task_lost_expunge_initial_delay", "1000",
      "--task_lost_expunge_interval", "1000"
    ) ++ extraMarathonParameters
    super.startMarathon(port, args: _*)
  }

  after {
    cleanUp()
    // Ensure that only slave1 is running
    if (ProcessKeeper.hasProcess(slave2)) stopMesos(slave2)
    if (!ProcessKeeper.hasProcess(slave1)) startSlave(slave1)
  }

  test("A task unreachable update will trigger a replacement task") {
    Given("a new app")
    val app = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)
    marathon.createAppV2(app)
    waitForEvent("deployment_success")
    val task = waitForTasks(app.id, 1).head

    When("the slave is partitioned")
    stopMesos(slave1)

    Then("the task is declared unreachable")
    waitForEventMatching("Task is declared unreachable") { matchEvent("TASK_UNREACHABLE", task) }

    And("a replacement task is started on a different slave")
    startSlave(slave2) // Start an alternative slave
    waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_RUNNING")
    val tasks = marathon.tasks(app.id).value
    tasks should have size 2
    tasks.groupBy(_.state).keySet should be(Set("TASK_RUNNING", "TASK_UNREACHABLE"))
    val replacement = tasks.find(_.state == "TASK_RUNNING").get

    When("the first slaves comes back")
    startSlave(slave1, wipe = false)

    Then("the task reappears as running")
    waitForEventMatching("Task is declared running") { matchEvent("TASK_RUNNING", task) }

    And("the replacement task is killed")
    waitForEventMatching("Replacement task is killed") { matchEvent("TASK_KILLED", replacement) }

    And("there is only one running task left")
    marathon.tasks(app.id).value should have size 1
    marathon.tasks(app.id).value.head.state should be("TASK_RUNNING")
  }

  def matchEvent(status: String, task: ITEnrichedTask): CallbackEvent => Boolean = { event =>
    event.info.get("taskStatus").contains(status) &&
      event.info.get("taskId").contains(task.id)
  }
}
