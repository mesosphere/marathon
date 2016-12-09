package mesosphere.marathon.api.v2

import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import mesosphere.marathon.test.{ MarathonSpec, Mockito }
import mesosphere.marathon.MarathonConf
import mesosphere.util.state.MesosLeaderInfo
import org.scalatest.{ GivenWhenThen, Matchers }

class InfoResourceTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    val f = new Fixture
    val resource = f.infoResource()
    f.auth.authenticated = false

    When("we try to fetch the info")
    val index = resource.index(f.auth.request)

    Then("we receive a NotAuthenticated response")
    index.getStatus should be(f.auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied") {
    Given("An unauthorized request")
    val f = new Fixture
    val resource = f.infoResource()
    f.auth.authenticated = true
    f.auth.authorized = false

    When("we try to fetch the info")
    val index = resource.index(f.auth.request)

    Then("we receive a NotAuthenticated response")
    index.getStatus should be(f.auth.UnauthorizedStatus)
  }

  class Fixture {
    val leaderInfo = mock[MesosLeaderInfo]
    val electionService = mock[ElectionService]
    val auth = new TestAuthFixture
    val config = mock[MarathonConf with HttpConf]
    val frameworkIdRepository = mock[FrameworkIdRepository]

    def infoResource() = new InfoResource(leaderInfo, frameworkIdRepository, electionService, auth.auth, auth.auth, config)
  }
}
