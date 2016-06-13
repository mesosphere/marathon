package mesosphere.marathon.api.v2

import mesosphere.chaos.http.HttpConf
import mesosphere.marathon._
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.test.Mockito
import org.scalatest.{ GivenWhenThen, Matchers }

class LeaderResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    val f = new Fixture
    val resource = f.leaderResource()
    f.auth.authenticated = false

    When("we try to get the leader info")
    val index = resource.index(f.auth.request)
    Then("we receive a NotAuthenticated response")
    index.getStatus should be(f.auth.NotAuthenticatedStatus)

    When("we try to delete the current leader")
    val delete = resource.delete(f.auth.request)
    Then("we receive a NotAuthenticated response")
    delete.getStatus should be(f.auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied") {
    Given("An unauthenticated request")
    val f = new Fixture
    val resource = f.leaderResource()
    f.auth.authenticated = true
    f.auth.authorized = false

    When("we try to get the leader info")
    val index = resource.index(f.auth.request)
    Then("we receive a Unauthorized response")
    index.getStatus should be(f.auth.UnauthorizedStatus)

    When("we try to delete the current leader")
    val delete = resource.delete(f.auth.request)
    Then("we receive a Unauthorized response")
    delete.getStatus should be(f.auth.UnauthorizedStatus)
  }

  class Fixture {
    AllConf.withTestConfig(Seq("--event_subscriber", "http_callback"))
    val schedulerService = mock[MarathonSchedulerService]
    val electionService = mock[ElectionService]
    val auth = new TestAuthFixture
    val config = mock[MarathonConf with HttpConf]
    def leaderResource() = new LeaderResource(schedulerService, electionService, config, auth.auth, auth.auth)
  }
}

