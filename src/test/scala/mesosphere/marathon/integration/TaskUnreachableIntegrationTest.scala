package mesosphere.marathon
package integration

import mesosphere.{ AkkaIntegrationFunTest }
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.integration.facades.ITEnrichedTask
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state.UnreachableStrategy

import scala.concurrent.duration._

@IntegrationTest
@UnstableTest
class TaskUnreachableIntegrationTest extends AkkaIntegrationFunTest with EmbeddedMarathonMesosClusterTest {

  override lazy val mesosNumMasters = 1
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

  // TODO unreachable tests for pods

  before {
    zkServer.start()
    mesosCluster.masters.foreach(_.start())
    mesosCluster.agents.head.start()
    mesosCluster.agents(1).stop()
    mesosCluster.waitForLeader().futureValue
    cleanUp()
  }

  test("A task unreachable update will trigger a replacement task") {
    Given("a new app with proper timeouts")
    val strategy = UnreachableStrategy(10.seconds, 5.minutes)
    val app = appProxy(testBasePath / "unreachable", "v1", instances = 1, healthCheck = None).copy(unreachableStrategy = strategy)
    waitForDeployment(marathon.createAppV2(app))
    val task = waitForTasks(app.id, 1).head

    When("the slave is partitioned")
    mesosCluster.agents(0).stop()

    Then("the task is declared unreachable")
    waitForEventMatching("Task is declared unreachable") { matchEvent("TASK_UNREACHABLE", task) }

    And("the task is declared unreachable inactive")
    waitForEventWith("instance_changed_event", _.info("condition") == "UnreachableInactive")

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

  // regression test for https://github.com/mesosphere/marathon/issues/4059
  test("Scaling down an app with constraints and unreachable task will succeed") {
    import mesosphere.marathon.Protos.Constraint
    Given("an app that is constrained to a unique hostname")
    val constraint: Constraint = Constraint.newBuilder
      .setField("hostname")
      .setOperator(Operator.UNIQUE)
      .setValue("")
      .build

    // start both slaves
    mesosCluster.agents.foreach(_.start())

    val strategy = UnreachableStrategy(5.minutes, 10.minutes)
    val app = appProxy(testBasePath / "regression", "v1", instances = 2, healthCheck = None)
      .copy(constraints = Set(constraint), unreachableStrategy = strategy)

    waitForDeployment(marathon.createAppV2(app))
    val enrichedTasks = waitForTasks(app.id, 2)
    val task = enrichedTasks.find(t => t.host == "0").getOrElse(throw new RuntimeException("No matching task found on slave1"))

    When("agent1 is stopped")
    mesosCluster.agents.head.stop()
    Then("one task is declared unreachable")
    waitForEventMatching("Task is declared lost") { matchEvent("TASK_UNREACHABLE", task) }

    When("We try to scale down to one instance")
    marathon.updateApp(app.id, AppUpdate(instances = Some(1)))
    waitForEventMatching("deployment to scale down should be triggered") { matchDeploymentStart(app.id.toString) }

    Then("the deployment will eventually finish")
    waitForEventMatching("app should be scaled and deployment should be finished") { matchDeploymentSuccess(1, app.id.toString) }
    marathon.listDeploymentsForBaseGroup().value should have size 0
  }

  def matchEvent(status: String, task: ITEnrichedTask): CallbackEvent => Boolean = { event =>
    event.info.get("taskStatus").contains(status) &&
      event.info.get("taskId").contains(task.id)
  }

  private def matchDeploymentSuccess(instanceCount: Int, appId: String): CallbackEvent => Boolean = { event =>
    val infoString = event.info.toString()
    event.eventType == "deployment_success" && infoString.contains(s"instances -> $instanceCount") && matchScaleApplication(infoString, appId)
  }

  private def matchDeploymentStart(appId: String): CallbackEvent => Boolean = { event =>
    val infoString = event.info.toString()
    event.eventType == "deployment_info" && matchScaleApplication(infoString, appId)
  }

  private def matchScaleApplication(infoString: String, appId: String): Boolean = {
    infoString.contains(s"List(Map(actions -> List(Map(action -> ScaleApplication, app -> $appId)))))")
  }
}
