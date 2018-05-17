package mesosphere.marathon
package api.v2

import akka.Done
import akka.actor.ActorSystem
import mesosphere.UnitTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.storage.repository.RuntimeConfigurationRepository
import mesosphere.marathon.test.{JerseyTest, SettableClock, SimulatedScheduler}
import scala.concurrent.Future
import scala.concurrent.duration._

class LeaderResourceTest extends UnitTest with JerseyTest {

  "LeaderResource" should {
    "access without authentication is denied" in {
      Given("An unauthenticated request")
      val f = new Fixture
      val resource = f.leaderResource()
      f.auth.authenticated = false

      When("we try to get the leader info")
      val index = syncRequest { resource.index(f.auth.request) }
      Then("we receive a NotAuthenticated response")
      index.getStatus should be(f.auth.NotAuthenticatedStatus)

      When("we try to delete the current leader")
      val delete = syncRequest { resource.delete(null, null, f.auth.request) }
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
      val index = syncRequest { resource.index(f.auth.request) }
      Then("we receive a Unauthorized response")
      index.getStatus should be(f.auth.UnauthorizedStatus)

      When("we try to delete the current leader")
      val delete = syncRequest { resource.delete(null, null, f.auth.request) }
      Then("we receive a Unauthorized response")
      delete.getStatus should be(f.auth.UnauthorizedStatus)
    }

    "should abdicate after 500ms if authorized" in {
      Given("An unauthenticated request")
      val f = new Fixture
      val resource = f.leaderResource()
      f.auth.authenticated = true
      f.auth.authorized = true
      f.electionService.isLeader.returns(true)
      f.runtimeRepo.store(any).returns(Future.successful(Done))

      When("we try to delete the current leader")
      val delete = syncRequest { resource.delete(null, null, f.auth.request) }
      Then("we receive a authorized response")
      delete.getStatus should be(200)
      verify(f.electionService, times(0)).abdicateLeadership()
      f.clock += 500.millis
      verify(f.electionService, times(1)).abdicateLeadership()
    }
  }

  class Fixture {
    val system = mock[ActorSystem]
    val schedulerService = mock[MarathonSchedulerService]
    val electionService = mock[ElectionService]
    val runtimeRepo = mock[RuntimeConfigurationRepository]
    val auth = new TestAuthFixture
    val config = AllConf.withTestConfig()
    val executionContext = ExecutionContexts.callerThread
    val clock = new SettableClock()
    val scheduler = new SimulatedScheduler(clock)
    def leaderResource() = new LeaderResource(electionService, config, runtimeRepo, auth.auth, auth.auth, scheduler)(
      executionContext)
  }
}

