package mesosphere.marathon
package api.v2

import mesosphere.UnitTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.event.HttpCallbackSubscriptionService

class EventSubscriptionsResourceTest extends UnitTest {
  class Fixture {
    val auth = new TestAuthFixture
    val config = AllConf.withTestConfig("--event_subscriber", "http_callback")
    val subscriptionService = mock[HttpCallbackSubscriptionService]
    def eventResource() = new EventSubscriptionsResource(config, auth.auth, auth.auth, subscriptionService)
  }

  "EventSubscriptionsResource" should {
    "access without authentication is denied" in {
      Given("An unauthenticated request")
      val f = new Fixture
      val resource = f.eventResource()
      f.auth.authenticated = false

      When("we try to subscribe")
      val subscribe = resource.subscribe(f.auth.request, "http://callback")
      Then("we receive a NotAuthenticated response")
      subscribe.getStatus should be(f.auth.NotAuthenticatedStatus)

      When("we try to list subscribers")
      val list = resource.listSubscribers(f.auth.request)
      Then("we receive a NotAuthenticated response")
      list.getStatus should be(f.auth.NotAuthenticatedStatus)

      When("we try to unsubscribe")
      val unsubscribe = resource.unsubscribe(f.auth.request, "http://callback")
      Then("we receive a NotAuthenticated response")
      unsubscribe.getStatus should be(f.auth.NotAuthenticatedStatus)
    }

    "access without authorization is denied" in {
      Given("An unauthenticated request")
      val f = new Fixture
      val resource = f.eventResource()
      f.auth.authenticated = true
      f.auth.authorized = false

      When("we try to subscribe")
      val subscribe = resource.subscribe(f.auth.request, "http://callback")
      Then("we receive a NotAuthenticated response")
      subscribe.getStatus should be(f.auth.UnauthorizedStatus)

      When("we try to list subscribers")
      val list = resource.listSubscribers(f.auth.request)
      Then("we receive a NotAuthenticated response")
      list.getStatus should be(f.auth.UnauthorizedStatus)

      When("we try to unsubscribe")
      val unsubscribe = resource.unsubscribe(f.auth.request, "http://callback")
      Then("we receive a NotAuthenticated response")
      unsubscribe.getStatus should be(f.auth.UnauthorizedStatus)
    }
  }
}
