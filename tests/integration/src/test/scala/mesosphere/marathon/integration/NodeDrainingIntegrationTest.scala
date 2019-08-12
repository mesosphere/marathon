package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.facades.{AppMockFacade, ITEnrichedTask}
import mesosphere.marathon.integration.setup.{EmbeddedMarathonTest, MesosConfig, RestResult}
import mesosphere.marathon.state.AbsolutePathId

class NodeDrainingIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  val pathId = AbsolutePathId("/back-the-world-off")

  override lazy val mesosConfig = MesosConfig(numAgents = 2)

  "Draining a node" should {
    "reset any backoff delay" in {
      Given("an app with high backoff setting")
      val app = appProxy(pathId, "v1", instances = 1, healthCheck = None,
        constraints = Set(Seq("hostname", "UNIQUE")),
        backoffSeconds = 3600, backoffFactor = 10, maxLaunchDelaySeconds = 86400)

      When("the app is deployed")
      val result = marathon.createAppV2(app)
      result should be(Created)

      val firstTask = eventually {
        val tasksResult: RestResult[List[ITEnrichedTask]] = marathon.tasks(pathId)
        tasksResult should be(OK)
        tasksResult.value.size shouldBe 1
        tasksResult.value.forall(_.launched) shouldBe true
        tasksResult.value.head
      }

      When("the first task fails")
      AppMockFacade(firstTask).suicide()

      Then("the task fail will result in a huge backoff")
      waitForStatusUpdates("TASK_FAILED")
      eventually {
        val queue = marathon.launchQueueForAppId(pathId).value
        queue should have size 1
        val queueItem = queue.head
        queueItem.delay.overdue shouldBe false
        logger.info(s"delay.timeLeftSeconds is at ${queueItem.delay.timeLeftSeconds}")
        queueItem.delay.timeLeftSeconds should be > 60
      }

      When("the node is drained")
      val agentId = firstTask.slaveId.getOrElse {
        fail(s"Cannot drain agent because $firstTask has no associated agentId")
      }
      mesos.drainAgent(agentId) should be(OK)

      Then("the delay is reset")
      eventually {
        val queue = marathon.launchQueueForAppId(pathId).value
        queue should have size 1
        val queueItem = queue.head
        queueItem.delay.overdue shouldBe false
        logger.info(s"delay.timeLeftSeconds is at ${queueItem.delay.timeLeftSeconds}")
        queueItem.delay.timeLeftSeconds should be <= 0
      }

      And("a new task is launched on the other agent")
      val secondTask = eventually {
        val tasksResult: RestResult[List[ITEnrichedTask]] = marathon.tasks(pathId)
        tasksResult should be(OK)
        tasksResult.value.size shouldBe 1
        tasksResult.value.forall(_.launched) shouldBe true
        tasksResult.value.head
      }

      secondTask.slaveId should not equal firstTask.slaveId
    }
  }
}
