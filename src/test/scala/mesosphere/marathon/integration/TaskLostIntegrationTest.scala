package mesosphere.marathon.integration

import mesosphere.marathon.integration.setup._
import org.scalatest.{ GivenWhenThen, Matchers }

class TaskLostIntegrationTest extends IntegrationFunSuite with WithMesosCluster with Matchers with GivenWhenThen {

  ignore("A task lost with mesos master failover will not kill the task") {
    Given("a new app")
    stopMesos(slave2)
    stopMesos(master2)
    val app = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)
    marathon.createAppV2(app)
    waitForEvent("deployment_success")
    val task = waitForTasks(app.id, 1).head

    When("We stop the slave, the task is declared lost")
    stopMesos(slave1)
    waitForEventMatching("Task is declared lost") { matchEvent("TASK_LOST", task) }

    And("The task is not removed from the task list")
    val lost = waitForTasks(app.id, 1).head
    lost.state should be("TASK_LOST")

    When("We do a Mesos Master failover and start the slave again")
    startMaster(master2, wipe = false)
    stopMesos(master1)
    startSlave(slave1, wipe = false)

    Then("The task reappears as running")
    waitForEventMatching("Task is declared running again") { matchEvent("TASK_RUNNING", task) }
  }

  test("A task lost with mesos master failover will start a replacement task") {
    Given("a new app")
    stopMesos(slave2)
    stopMesos(master2)
    val app = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)
    marathon.createAppV2(app)
    waitForEvent("deployment_success")
    val task = waitForTasks(app.id, 1).head

    When("We stop the slave, the task is declared lost")
    stopMesos(slave1)
    startSlave(slave2, wipe = false)
    waitForEventMatching("Task is declared lost") { matchEvent("TASK_LOST", task) }

    And("A replacement task is started")
    waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_RUNNING")
    val tasks = marathon.tasks(app.id).value
    tasks should have size 2
    tasks.groupBy(_.state).keySet should be(Set("TASK_RUNNING", "TASK_LOST"))
    val replacement = tasks.find(_.state == "TASK_RUNNING").get

    When("We do a Mesos Master failover and start the slave again")
    startMaster(master2, wipe = false)
    stopMesos(master1)
    startSlave(slave1, wipe = false)

    Then("The task reappears as running and the replacement is killed")
    var isRunning = false
    var isKilled = false
    waitForEventMatching("Original task is running and replacement task is killed") { event =>
      isRunning |= matchEvent("TASK_RUNNING", task)(event)
      isKilled |= matchEvent("TASK_KILLED", replacement)(event)
      isRunning && isKilled
    }
    waitForTasks(app.id, 1).head should be(task)
  }

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
      "--min_revive_offers_interval", "100"
    ) ++ extraMarathonParameters
    super.startMarathon(port, args: _*)
  }

  def matchEvent(status: String, task: ITEnrichedTask): CallbackEvent => Boolean = { event =>
    event.info.get("taskStatus").contains(status) &&
      event.info.get("taskId").contains(task.id)
  }

}
