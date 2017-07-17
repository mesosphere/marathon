package mesosphere.marathon
package api.v2

import akka.actor.ActorSystem
import mesosphere.UnitTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.storage.repository.RuntimeConfigurationRepository

class LeaderResourceTest extends UnitTest {

  "LeaderResource" should {
    "access without authentication is denied" in {
      Given("An unauthenticated request")
      val f = new Fixture
      val resource = f.leaderResource()
      f.auth.authenticated = false

      When("we try to get the leader info")
      val index = resource.index(f.auth.request)
      Then("we receive a NotAuthenticated response")
      index.getStatus should be(f.auth.NotAuthenticatedStatus)

      When("we try to delete the current leader")
      val delete = resource.delete(null, null, f.auth.request)
      Then("we receive a NotAuthenticated response")
      delete.getStatus should be(f.auth.NotAuthenticatedStatus)
    }

    "access without authorization is denied" in {
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
      val delete = resource.delete(null, null, f.auth.request)
      Then("we receive a Unauthorized response")
      delete.getStatus should be(f.auth.UnauthorizedStatus)
    }
  }

  class Fixture {
    val system = mock[ActorSystem]
    val schedulerService = mock[MarathonSchedulerService]
    val electionService = mock[ElectionService]
    val runtimeRepo = mock[RuntimeConfigurationRepository]
    val auth = new TestAuthFixture
    val config = AllConf.withTestConfig()
    def leaderResource() = new LeaderResource(system, electionService, config, runtimeRepo, auth.auth, auth.auth)
  }
}

