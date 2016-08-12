package mesosphere.marathon.integration

import mesosphere.marathon.integration.facades.ITEnrichedTask
import mesosphere.marathon.integration.setup._
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }

import scala.concurrent.duration._

class TaskLostIntegrationTest extends IntegrationFunSuite with WithMesosCluster with Matchers with GivenWhenThen with BeforeAndAfter {

  after {
    cleanUp()
    if (ProcessKeeper.hasProcess(slave2)) stopMesos(slave2)
    if (ProcessKeeper.hasProcess(master2)) stopMesos(master2)
    if (!ProcessKeeper.hasProcess(master1)) startMaster(master1)
    if (!ProcessKeeper.hasProcess(slave1)) startSlave(slave1)
  }

  ignore("A task lost with mesos master failover will not kill the task - https://github.com/mesosphere/marathon/issues/4214") {
    Given("a new app")
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

  test("A task lost with mesos master failover will expunge the task after gc timeout - https://github.com/mesosphere/marathon/issues/4212") {
    Given("a new app")
    val app = appProxy(testBasePath / "app", "v1", instances = 1, withHealth = false)
    marathon.createAppV2(app)
    waitForEvent("deployment_success")
    val task = waitForTasks(app.id, 1).head

    When("We stop the slave, the task is declared lost")
    stopMesos(slave1)
    startSlave(slave2, wipe = false)
    waitForEventMatching("Task is declared lost") { matchEvent("TASK_LOST", task) }

    Then("The task is killed due to GC timeout and a replacement is started")
    val marathonName = ProcessKeeper.processNames.find(_.startsWith("marathon")).getOrElse(fail("no Marathon process found"))
    waitForProcessLogMessage(marathonName, maxWait = 1.minute) { line =>
      line.contains(task.id) && line.contains("will be expunged")
    }
    val replacement = waitForTasks(app.id, 1).head
    replacement should not be task
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
      "--min_revive_offers_interval", "100",
      "--task_lost_expunge_gc", "30000",
      "--task_lost_expunge_initial_delay", "1000",
      "--task_lost_expunge_interval", "1000"
    ) ++ extraMarathonParameters
    super.startMarathon(port, args: _*)
  }

  def matchEvent(status: String, task: ITEnrichedTask): CallbackEvent => Boolean = { event =>
    event.info.get("taskStatus").contains(status) &&
      event.info.get("taskId").contains(task.id)
  }

}
