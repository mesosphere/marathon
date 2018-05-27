package mesosphere.marathon
package api.v2

import mesosphere.UnitTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import mesosphere.marathon.test.{JerseyTest, MarathonTestHelper}
import mesosphere.util.state.{FrameworkId, MesosLeaderInfo}
import play.api.libs.json.{JsObject, JsString, Json}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class InfoResourceTest extends UnitTest with JerseyTest {

  "InfoResource" should {
    "access without authentication is denied" in {
      Given("An unauthenticated request")
      val f = new Fixture
      val resource = f.infoResource()
      f.auth.authenticated = false

      When("we try to fetch the info")
      val index = syncRequest { resource.index(f.auth.request) }

      Then("we receive a NotAuthenticated response")
      index.getStatus should be(f.auth.NotAuthenticatedStatus)
    }

    "access without authorization is denied" in {
      Given("An unauthorized request")
      val f = new Fixture
      val resource = f.infoResource()
      f.auth.authenticated = true
      f.auth.authorized = false

      When("we try to fetch the info")
      val index = syncRequest { resource.index(f.auth.request) }

      Then("we receive a NotAuthenticated response")
      index.getStatus should be(f.auth.UnauthorizedStatus)
    }

    "zk credentials are not leaked" in {
      Given("A request")
      val f = new Fixture
      f.leaderInfo.currentLeaderUrl returns Some("http://127.0.0.1:5050")
      f.frameworkIdRepository.get returns Future.successful(Some(FrameworkId("dummy-uuid")))
      f.electionService.isLeader returns true
      f.electionService.leaderHostPort returns Some("127.0.0.1:8080")
      f.auth.authenticated = true
      f.auth.authorized = true
      f.config = MarathonTestHelper.makeConfig(
        "--master", "zk://root:password@127.0.0.1:2181/mesos",
        "--zk", "zk://root:password@127.0.0.1:2181/marathon")
      val resource = f.infoResource()

      When("the info is fetched")
      val response = syncRequest { resource.index(f.auth.request) }

      Then("there must not be the credentials in the Mesos ZK connection string")
      response.getStatus should be(200)

      val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
      parsedResponse should be (defined)
      val responseObject = parsedResponse.get.asInstanceOf[JsObject]
      (responseObject \ "marathon_config" \ "master").get.asInstanceOf[JsString].value shouldEqual "zk://127.0.0.1:2181/mesos"
      (responseObject \ "zookeeper_config" \ "zk").get.asInstanceOf[JsString].value shouldEqual "zk://127.0.0.1:2181/marathon"
    }
  }

  class Fixture {
    val leaderInfo = mock[MesosLeaderInfo]
    val electionService = mock[ElectionService]
    val auth = new TestAuthFixture
    var config = mock[MarathonConf with HttpConf]
    val frameworkIdRepository = mock[FrameworkIdRepository]

    def infoResource() = new InfoResource(leaderInfo, frameworkIdRepository, electionService, auth.auth, auth.auth, config)
  }
}
