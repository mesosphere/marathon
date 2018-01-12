package mesosphere.marathon
package integration

import mesosphere.{ AkkaIntegrationFunTest, SerialIntegrationTag }
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state._

import scala.concurrent.duration._

@SerialIntegrationTest
class GracefulTaskKillIntegrationTest extends AkkaIntegrationFunTest with EmbeddedMarathonTest {

  before {
    cleanUp()
  }

  // this command simulates a 'long terminating' application
  // note: Integration test does not interpret symbolic names (SIGTERM=15), therefore signal 15 is used.
  val taskKillGraceDuration = 4
  val taskKillGracePeriod = taskKillGraceDuration.seconds
  val appCommand: String = s"""trap \"sleep ${taskKillGraceDuration + 1}\" 15 && sleep 100000"""

  test("create a 'long terminating' app with custom taskKillGracePeriod duration", SerialIntegrationTag) {
    Given("a new 'long terminating' app with taskKillGracePeriod set to 10 seconds")
    val app = AppDefinition(
      testBasePath / "app",
      cmd = Some(appCommand),
      taskKillGracePeriod = Some(taskKillGracePeriod))

    When("The app is deployed")
    val result = marathon.createAppV2(app)

    Then("The app is created")
    result.code should be (201) //Created
    waitForDeployment(result)
    waitForTasks(app.id, 1) //make sure, the app has really started
    val taskId = marathon.tasks(app.id).value.head.id

    When("a task of an app is killed")
    val taskKillSentTimestamp = Timestamp.now()
    marathon.killTask(app.id, taskId).code should be (200)

    val taskKilledEvent = waitForEventWith(
      "status_update_event",
      _.info("taskStatus") == "TASK_KILLED",
      maxWait = taskKillGracePeriod.plus(2.seconds))

    val taskKilledReceivedTimestamp = Timestamp(taskKilledEvent.info("timestamp").toString)
    val waitedForTaskKilledEvent = taskKillSentTimestamp until taskKilledReceivedTimestamp

    // the task_killed event should occur at least 10 seconds after sending it
    waitedForTaskKilledEvent.toMillis should be >= taskKillGracePeriod.toMillis
  }

  test("create a 'short terminating' app with custom taskKillGracePeriod duration", SerialIntegrationTag) {
    Given("a new 'short terminating' app with taskKillGracePeriod set to 10 seconds")
    val app = AppDefinition(
      testBasePath / "app",
      cmd = Some("sleep 100000"),
      taskKillGracePeriod = Some(taskKillGracePeriod))

    When("The app is deployed")
    val result = marathon.createAppV2(app)

    Then("The app is created")
    result.code should be (201) //Created
    waitForDeployment(result)
    waitForTasks(app.id, 1) //make sure, the app has really started
    val taskId = marathon.tasks(app.id).value.head.id

    When("a task of an app is killed")
    marathon.killTask(app.id, taskId).code should be (200)
    val taskKillSentTimestamp = Timestamp.now()

    val taskKilledEvent = waitForEventWith(
      "status_update_event",
      _.info("taskStatus") == "TASK_KILLED",
      maxWait = taskKillGracePeriod.plus(2.seconds))

    val taskKilledReceivedTimestamp = Timestamp(taskKilledEvent.info("timestamp").toString)
    val waitedForTaskKilledEvent = taskKillSentTimestamp until taskKilledReceivedTimestamp

    // the task_killed event should occur instantly or at least smaller as taskKillGracePeriod,
    // because the app terminates shortly
    waitedForTaskKilledEvent.toMillis should be < taskKillGracePeriod.toMillis withClue "the task kill event took longer than the task kill grace period"
  }

  def healthCheck = MarathonHttpHealthCheck(
    gracePeriod = 20.second,
    interval = 1.second,
    maxConsecutiveFailures = 10,
    portIndex = Some(PortReference(0)))
}
