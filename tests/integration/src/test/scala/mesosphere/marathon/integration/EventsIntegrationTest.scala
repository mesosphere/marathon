package mesosphere.marathon
package integration

import akka.stream.scaladsl.Sink
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state.PathId
import mesosphere.marathon.state.PathId._

import scala.concurrent.Future

class EventsIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  def appId(suffix: String): PathId = testBasePath / s"app-$suffix"

  "Filter events" should {
    "receive only app deployment event" in {
      Given("a new event source without filter is connected")
      val allEvents = marathon.events().futureValue
        .map(_.eventType)
        .takeWhile(_ != "deployment_success", inclusive = true)
        .runWith(Sink.seq)

      Given("a new event source with filter is connected")
      val filteredEvent: Future[String] = marathon.events(Seq("deployment_success")).futureValue
        .map(_.eventType)
        .runWith(Sink.head)

      When("the app is created")
      val app = appProxy(appId("with-one-deployment-event"), "v1", instances = 1, healthCheck = None)
      val result = marathon.createAppV2(app)

      And("we wait for deployment")
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1)

      Then("deployment events should be filtered")
      val allEventsResult = allEvents.futureValue
      filteredEvent.futureValue shouldBe "deployment_success"
      allEventsResult.length should be > 1
    }
  }

  "Subscribe to events" should {
    "always receive only small events" in {
      Given("a new event source is connected")
      val events = marathon.events().futureValue
        .takeWhile(_.eventType != "deployment_success", inclusive = true)
        .runWith(Sink.seq)

      When("the app is created")
      val app = appProxy(appId("deployment-event"), "v1", instances = 1, healthCheck = None)
      val result = marathon.createAppV2(app)

      And("we wait for deployment")
      waitForDeployment(result)
      waitForTasks(app.id.toPath, 1)

      Then("deployment events should not include the original and target groups")
      val eventsResult = events.futureValue
      val deploymentSuccessEvent = eventsResult.find(_.eventType == "deployment_success")
      deploymentSuccessEvent.value.info("plan").asInstanceOf[Map[String, Any]].keys should not contain ("original")
    }
  }
}
