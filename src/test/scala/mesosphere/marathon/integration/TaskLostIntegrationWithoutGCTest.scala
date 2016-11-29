package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationFunTest
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.integration.facades.ITEnrichedTask
import mesosphere.marathon.integration.setup._

@IntegrationTest
@UnstableTest
class TaskLostIntegrationWithoutGCTest extends AkkaIntegrationFunTest with EmbeddedMarathonMesosClusterTest {
  override lazy val mesosNumMasters = 2
  before {
    cleanUp()
    mesosCluster.agents(1).stop()
    mesosCluster.masters(1).stop()
    mesosCluster.masters(0).start()
    mesosCluster.agents(0).start()
  }

  test("A task unreachable with mesos master failover will not expunge the task and a scale down will succeed") {
    // TODO: the test should also run with 1 task and one agent
    mesosCluster.agents(1).start()

    Given("a new app")
    val appId = testBasePath / "app"
    val app = appProxy(appId, "v1", instances = 2, healthCheck = None).copy(
      cmd = Some("sleep 1000"),
      constraints = Set(
        // make sure each agent runs one task so that no task is launched after one agent goes down
        Protos.Constraint.newBuilder().setField("hostname").setOperator(Operator.UNIQUE).build())
    )
    marathon.createAppV2(app)

    When("the deployment is finished")
    waitForEvent("deployment_success")
    val tasks0 = waitForTasks(app.id, 2)

    Then("there are 2 running tasks on 2 agents")
    tasks0 should have size 2
    tasks0.forall(_.state == "TASK_RUNNING") shouldBe true
    val task = tasks0.find(_.host == "0").getOrElse(fail("no task was started on slave1"))
    tasks0.find(_.host == "1").getOrElse(fail("no task was started on slave2"))

    When("We stop one agent, one task is declared unreachable")
    mesosCluster.agents(0).stop()
    waitForEventMatching("Task is declared unreachable") { matchEvent("TASK_UNREACHABLE", task) }

    And("The task is NOT removed from the task list")
    val tasks1 = waitForTasks(app.id, 2)
    tasks1 should have size 2
    tasks1.exists(_.state == "TASK_UNREACHABLE") shouldBe true

    When("We scale down the app")
    marathon.updateApp(appId, AppUpdate(instances = Some(1)))

    Then("The deployment finishes")
    waitForEvent("deployment_success")

    And("The lost task is expunged")
    val tasks2 = marathon.tasks(app.id).value
    tasks2 should have size 1
    tasks2.exists(_.state == "TASK_RUNNING") shouldBe true
  }

  override val marathonArgs: Map[String, String] = Map(
    "reconciliation_initial_delay" -> "5000",
    "reconciliation_interval" -> "5000",
    "scale_apps_initial_delay" -> "5000",
    "scale_apps_interval" -> "5000",
    "min_revive_offers_interval" -> "100",
    "task_lost_expunge_gc" -> "300000000",
    "task_lost_expunge_initial_delay" -> "1000000",
    "task_lost_expunge_interval" -> "60000"
  )

  def matchEvent(status: String, task: ITEnrichedTask): CallbackEvent => Boolean = { event =>
    event.info.get("taskStatus").contains(status) &&
      event.info.get("taskId").contains(task.id)
  }

}
