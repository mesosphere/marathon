package mesosphere.marathon.api.v2

import mesosphere.marathon._
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.event.HttpCallbackSubscriptionService
import mesosphere.marathon.test.Mockito
import org.scalatest.{ GivenWhenThen, Matchers }

class EventSubscriptionsResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {

  test("access without authentication is denied") {
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

  test("access without authorization is denied") {
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

  class Fixture {
    AllConf.withTestConfig(Seq("--event_subscriber", "http_callback"))
    val auth = new TestAuthFixture
    val config = mock[MarathonConf]
    val subscriptionService = mock[HttpCallbackSubscriptionService]
    def eventResource() = new EventSubscriptionsResource(config, auth.auth, auth.auth, subscriptionService)
  }
}

