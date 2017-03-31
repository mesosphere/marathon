package mesosphere.marathon
package integration

import java.util.UUID

import akka.stream.scaladsl.Sink
import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup._
import mesosphere.marathon.state.PathId
import mesosphere.marathon.state.PathId._

import scala.concurrent.Future
import scala.concurrent.duration._

@IntegrationTest
class EventsIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  before(cleanUp())

  def appId(): PathId = testBasePath / s"app-${UUID.randomUUID}"

  "Filter events" should {
    "receive only app deployment event" in {
      Given("a duration limit for connect")
      val atMost = patienceConfig.timeout.toMillis.millis

      Given("a new event source without filter is connected")
      val allEvents = marathon.events().futureValue
        .map(_.eventType)
        .takeWhile(_ != "deployment_success", inclusive = true)
        .runWith(Sink.seq)

      Given("a new event source with filter is connected")
      val filteredEvent: Future[String] = marathon.events("deployment_success").futureValue
        .map(_.eventType)
        .runWith(Sink.head)

      When("the app is created")
      val app = appProxy(appId(), "v1", instances = 1, healthCheck = None)
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
}