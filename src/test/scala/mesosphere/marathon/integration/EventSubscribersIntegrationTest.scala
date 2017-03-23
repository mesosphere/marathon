package mesosphere.marathon
package integration

import mesosphere.AkkaIntegrationTest
import mesosphere.marathon.integration.setup.EmbeddedMarathonTest

@IntegrationTest
class EventSubscribersIntegrationTest extends AkkaIntegrationTest with EmbeddedMarathonTest {

  before(cleanUp())

  "EventSubscribers" should {
    "adding an event subscriber" in {
      When("an event subscriber is added")
      marathon.subscribe("http://localhost:1337").code should be(200)

      Then("a notification should be sent to all the subscribers")
      waitForEventWith("subscribe_event", _.info.exists(_ == "callbackUrl" -> "http://localhost:1337"))

      And("the subscriber should show up in the list of subscribers")
      marathon.listSubscribers.value.urls should contain("http://localhost:1337")

      // Cleanup
      marathon.unsubscribe("http://localhost:1337")
    }

    "adding an invalid event subscriber" in {
      When("an invalid event subscriber is added")
      marathon.subscribe("invalid%20URL").code should be(422)

      Then("the subscriber should not show up in the list of subscribers")
      marathon.listSubscribers.value.urls should not contain "invalid URL"
    }

    "removing an event subscriber" in {
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
}
