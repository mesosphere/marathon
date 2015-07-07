package mesosphere.marathon.integration

import mesosphere.marathon.integration.setup.{ IntegrationFunSuite, SingleMarathonIntegrationTest }
import org.scalatest._

class EventSubscribersIntegrationTest
    extends IntegrationFunSuite
    with SingleMarathonIntegrationTest
    with Matchers
    with BeforeAndAfter
    with GivenWhenThen {

  before(cleanUp())

  test("adding an event subscriber") {
    When("an event subscriber is added")
    marathon.subscribe("http://localhost:1337").code should be(200)

    Then("a notification should be sent to all the subscribers")
    waitForEventWith("subscribe_event", _.info.exists(_ == "callbackUrl" -> "http://localhost:1337"))

    And("the subscriber should show up in the list of subscribers")
    marathon.listSubscribers.value.urls should contain("http://localhost:1337")

    // Cleanup
    marathon.unsubscribe("http://localhost:1337")
  }

  test("removing an event subscriber") {
    When("an event subscriber is removed")
    marathon.subscribe("http://localhost:1337").code should be(200)
    marathon.listSubscribers.value.urls should contain("http://localhost:1337")
    marathon.unsubscribe("http://localhost:1337").code should be(200)

    Then("a notification should be sent to all the subscribers")
    waitForEventWith("subscribe_event", _.info.exists(_ == "callbackUrl" -> "http://localhost:1337"))

    And("the subscriber should not show up in the list of subscribers")
    marathon.listSubscribers.value.urls shouldNot contain("http://localhost:1337")
  }
}
