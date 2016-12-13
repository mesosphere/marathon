package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationFunTest
import mesosphere.marathon.integration.facades.ITEnrichedTask
import mesosphere.marathon.integration.setup._

/**
  * Integration test to simulate the issues discovered a verizon where a network partition caused Marathon to be
  * separated from ZK and the leading Master.  During this separation the agents were partitioned from the Master.  Marathon was
  * bounced, then the network connectivity was re-established.  At which time the Mesos kills tasks on the slaves and marathon never
  * restarts them.
  *
  * This collection of integration tests is intended to go beyond the experience at Verizon.  The network partition in these tests
  * are simulated with a disconnection from the processes.
  */
@IntegrationTest
@UnstableTest
class NetworkPartitionIntegrationTest extends AkkaIntegrationFunTest with EmbeddedMarathonMesosClusterTest {

  before {
    mesosCluster.masters.foreach(_.start())
    mesosCluster.agents.foreach(_.start())
    zkServer.start()
    cleanUp()
  }

  test("Loss of ZK and Loss of Slave will not kill the task when slave comes back") {
    Given("a new app")
    val app = appProxy(testBasePath / "app", "v1", instances = 1, healthCheck = None)
    waitForDeployment(marathon.createAppV2(app))
    val task = waitForTasks(app.id, 1).head

    When("We stop the slave, the task is declared unreachable")
    // stop zk
    mesosCluster.agents(0).stop()
    waitForEventMatching("Task is declared unreachable") {
      matchEvent("TASK_UNREACHABLE", task)
    }

    And("The task is shows in marathon as unreachable")
    val lost = waitForTasks(app.id, 1).head
    lost.state should be("TASK_UNREACHABLE")

    When("the master bounds and the slave starts again")
    // network partition of zk
    zkServer.stop()
    // and master
    mesosCluster.masters(0).stop()

    // zk back in service
    zkServer.start()

    mesosCluster.masters(0).start()
    mesosCluster.agents(0).start()

    // bring up the cluster
    Then("The task reappears as running")
    waitForEventMatching("Task is declared running again") {
      matchEvent("TASK_RUNNING", task)
    }
  }

  def matchEvent(status: String, task: ITEnrichedTask): CallbackEvent => Boolean = { event =>
    event.info.get("taskStatus").contains(status) &&
      event.info.get("taskId").contains(task.id)
  }

  override val marathonArgs: Map[String, String] = Map(
    "reconciliation_initial_delay" -> "5000",
    "reconciliation_interval" -> "5000",
    "scale_apps_initial_delay" -> "5000",
    "scale_apps_interval" -> "5000",
    "min_revive_offers_interval" -> "100"
  )
}
