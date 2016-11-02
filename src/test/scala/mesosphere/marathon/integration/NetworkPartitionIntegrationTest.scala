package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.ITEnrichedTask
import mesosphere.marathon.integration.setup._
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Second, Seconds, Span }
import mesosphere.marathon.state.PathId._

/**
  * Integration test to simulate the issues discovered a verizon where a network partition caused Marathon to be
  * separated from ZK and the leading Master.  During this separation the agents were partitioned from the Master.  Marathon was
  * bounced, then the network connectivity was re-established.  At which time the Mesos kills tasks on the slaves and marathon never
  * restarts them.
  *
  * This collection of integration tests is intended to go beyond the experience at Verizon.  The network partition in these tests
  * are simulated with a disconnection from the processes.
  */
@UnstableTest
class NetworkPartitionIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest with Eventually {

  override implicit lazy val patienceConfig = PatienceConfig(timeout = Span(50, Seconds), interval = Span(1, Second))

  override lazy val mesosNumMasters = 1
  override lazy val mesosNumSlaves = 1

  override val marathonArgs: Map[String, String] = Map(
    "reconciliation_initial_delay" -> "5000",
    "reconciliation_interval" -> "1000",
    "scale_apps_initial_delay" -> "5000",
    "scale_apps_interval" -> "5000",
    "min_revive_offers_interval" -> "100",
    "task_lost_expunge_gc" -> "30000",
    "task_lost_expunge_initial_delay" -> "1000",
    "task_lost_expunge_interval" -> "1000",
    "zk_timeout" -> "2000"
  )

  before {
    mesosCluster.masters.foreach(_.start())
    mesosCluster.agents.foreach(_.start())
    zkServer.start()
    cleanUp()
  }

  "Network Partitioning" should {
    "Loss of ZK and Loss of Slave will not kill the task when slave comes back" in {
      Given("a new app")
      val app = appProxy(testBasePath / "app", "v1", instances = 1, healthCheck = None)
      waitForDeployment(marathon.createAppV2(app))
      val task = waitForTasks(app.id.toPath, 1).head

      When("We stop the slave, the task is declared unreachable")
      // stop zk
      mesosCluster.agents(0).stop()
      waitForEventMatching("Task is declared unreachable") {
        matchEvent("TASK_UNREACHABLE", task)
      }

      And("The task is shows in marathon as unreachable")
      val lost = waitForTasks(app.id.toPath, 1).head
      lost.state should be("TASK_UNREACHABLE")

      When("Zookeeper and Mesos are partitioned")
      // network partition of zk
      zkServer.stop()
      // and master
      mesosCluster.masters(0).stop()

      Then("Marathon suicides")
      eventually {
        marathonServer.isRunning should be(false)
      }
      // Proper clean up
      marathonServer.stop()

      When("Zookeeper and Mesos come back")
      zkServer.start()

      mesosCluster.masters(0).start()
      mesosCluster.agents(0).start()

      And("Marathon is restarted by Systemd")
      // Simulate Systemd rebooting Marathon
      marathonServer.start()
      eventually {
        marathonServer.isRunning should be(true)
      }

      Then("The task reappears as running")
      waitForEventMatching("Task is declared running again") {
        matchEvent("TASK_RUNNING", task)
      }
    }
  }
  def matchEvent(status: String, task: ITEnrichedTask): CallbackEvent => Boolean = { event =>
    event.info.get("taskStatus").contains(status) &&
      event.info.get("taskId").contains(task.id)
  }
}
