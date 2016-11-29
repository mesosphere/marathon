package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationFunTest
import mesosphere.marathon.integration.facades.ITEnrichedTask
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state.UnreachableStrategy

import scala.concurrent.duration._

@IntegrationTest
class TaskUnreachableIntegrationTest extends AkkaIntegrationFunTest with EmbeddedMarathonMesosClusterTest {

  override lazy val mesosNumMasters = 2
  override lazy val mesosNumSlaves = 2

  override val marathonArgs: Map[String, String] = Map(
    "reconciliation_initial_delay" -> "5000",
    "reconciliation_interval" -> "5000",
    "scale_apps_initial_delay" -> "5000",
    "scale_apps_interval" -> "5000",
    "min_revive_offers_interval" -> "100",
    "task_lost_expunge_gc" -> "30000",
    "task_lost_expunge_initial_delay" -> "1000",
    "task_lost_expunge_interval" -> "1000"
  )

  before {
    mesosCluster.agents(1).stop()
    mesosCluster.masters(1).stop()
    mesosCluster.masters.head.start()
    mesosCluster.agents.head.start()
    mesosCluster.waitForLeader().futureValue
  }

  after {
    // restoring the entire cluster increases the changes cleanUp will succeed.
    mesosCluster.masters.foreach(_.start())
    mesosCluster.agents.foreach(_.start())
    mesosCluster.waitForLeader().futureValue
    cleanUp()
  }

  test("A task unreachable update will trigger a replacement task") {
    Given("a new app with proper timeouts")
    val strategy = UnreachableStrategy(10.seconds, 5.minutes)
    val app = appProxy(testBasePath / "app", "v1", instances = 1).copy(unreachableStrategy = strategy)
    waitForDeployment(marathon.createAppV2(app))
    val task = waitForTasks(app.id, 1).head

    When("the slave is partitioned")
    mesosCluster.agents(0).stop()

    Then("the task is declared unreachable")
    waitForEventMatching("Task is declared unreachable") { matchEvent("TASK_UNREACHABLE", task) }

    // InstanceChange events with UnreachableInactive are not propagated yet.
    // See https://mesosphere.atlassian.net/browse/MARATHON-1289
    //waitForEventWith("instance_changed_event", _.info("condition") == "UnreachableInactive")

    And("a replacement task is started on a different slave")
    mesosCluster.agents(1).start() // Start an alternative slave
    waitForEventWith("status_update_event", _.info("taskStatus") == "TASK_RUNNING")
    val tasks = marathon.tasks(app.id).value
    tasks should have size 2
    tasks.groupBy(_.state).keySet should be(Set("TASK_RUNNING", "TASK_UNREACHABLE"))
    val replacement = tasks.find(_.state == "TASK_RUNNING").get

    When("the first slaves comes back")
    mesosCluster.agents(0).start()

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
