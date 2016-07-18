package mesosphere.marathon.core.event.impl.stream

import javax.servlet.http.HttpServletResponse

import akka.actor.ActorRef
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon._
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.test.Mockito
import org.scalatest.{ GivenWhenThen, Matchers }

class HttpEventStreamServletTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    val f = new Fixture
    val resource = f.streamServlet()
    val response = mock[HttpServletResponse]
    f.auth.authenticated = false

    When("we try to attach to the event stream")
    resource.doGet(f.auth.request, response)

    Then("we receive a NotAuthenticated response")
    verify(response).setStatus(f.auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied") {
    Given("An unauthorized request")
    val f = new Fixture
    val resource = f.streamServlet()
    val response = mock[HttpServletResponse]
    f.auth.authenticated = true
    f.auth.authorized = false

    When("we try to attach to the event stream")
    resource.doGet(f.auth.request, response)

    Then("we receive a Unauthorized response")
    verify(response).setStatus(f.auth.UnauthorizedStatus)
  }

  class Fixture {
    AllConf.withTestConfig(Seq("--event_subscriber", "http_callback"))
    val actor = mock[ActorRef]
    val auth = new TestAuthFixture
    val config = mock[MarathonConf with HttpConf]
    def streamServlet() = new HttpEventStreamServlet(actor, config, auth.auth, auth.auth)
  }
}

