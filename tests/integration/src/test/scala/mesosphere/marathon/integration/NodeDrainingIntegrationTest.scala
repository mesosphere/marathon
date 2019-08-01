package mesosphere.marathon
package integration

import akka.stream.scaladsl.Sink
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest
import mesosphere.marathon.raml.App
import mesosphere.marathon.state.PathId

class NodeDrainingIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  val pathId = PathId("/back-the-world-off")
  val app = App(
    id = pathId.toString,
    cmd = Some("sleep 1 && exit 1"),
    backoffSeconds = 3600,
    backoffFactor = 10,
    maxLaunchDelaySeconds = 86400)

  "Draining a node" should {
    "reset any backoff delay" in {
      Given("an app with high backoff setting")

      // attach to the event stream so we can figure out a valid agentId w/o
      // having to query for short lived tasks
      val events = marathon.events().futureValue
        .takeWhile(_.eventType != "deployment_success", inclusive = true)
        .runWith(Sink.seq)

      When("the app is deployed")
      val result = marathon.createAppV2(app)
      result should be(Created)

      val maybeAgentId = events.futureValue.find(_.eventType == "status_update_event").map(_.info("slaveId").asInstanceOf[String])
      maybeAgentId should not be empty
      val agentId = maybeAgentId.value

      Then("the task will eventually fail resulting in a huge backoff")
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
      mesos.drainAgent(agentId, maxGracePeriod = Some(42)).success shouldBe true

      Then("the delay is reset")
      eventually {
        val queue = marathon.launchQueueForAppId(pathId).value
        queue should have size 1
        val queueItem = queue.head
        queueItem.delay.overdue shouldBe false
        logger.info(s"delay.timeLeftSeconds is at ${queueItem.delay.timeLeftSeconds}")
        queueItem.delay.timeLeftSeconds should be <= 0
      }
    }
  }
}
