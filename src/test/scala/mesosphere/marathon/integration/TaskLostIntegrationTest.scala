package mesosphere.marathon.integration

import mesosphere.marathon.integration.setup._
import org.scalatest.{ GivenWhenThen, Matchers }

class TaskLostIntegrationTest extends IntegrationFunSuite with WithMesosCluster with Matchers with GivenWhenThen {

  test("A task lost will kill the task") {
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
    waitForTasks(app.id, 0) //the task is declared as lost

    Then("We do a Mesos Master failover and the task reappers as running")
    startMaster(master2, wipe = false)
    stopMesos(master1)
    startSlave(slave1, wipe = false)

    And("The task is killed, since it is unknown to Marathon")
    waitForProcessLogMessage(master2) { log =>
      log.contains("Status update TASK_KILLED") && log.contains(task.id)
    }
  }

  //override to start marathon with a limited reconciliation interval
  override def startMarathon(port: Int, ignore: String*): Unit = {
    val args = List(
      "--master", config.master,
      "--event_subscriber", "http_callback",
      "--access_control_allow_origin", "*",
      "--reconciliation_initial_delay", "5000",
      "--reconciliation_interval", "5000",
      "--min_revive_offers_interval", "100"
    ) ++ extraMarathonParameters
    super.startMarathon(port, args: _*)
  }

  def matchEvent(status: String, task: ITEnrichedTask): CallbackEvent => Boolean = { event =>
    event.info.get("taskStatus").contains(status) &&
      event.info.get("taskId").contains(task.id)
  }

}
