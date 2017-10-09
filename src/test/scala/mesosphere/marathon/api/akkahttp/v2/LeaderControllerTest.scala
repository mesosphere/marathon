package mesosphere.marathon
package api.akkahttp.v2

import akka.Done
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.marathon.api.{ JsonTestHelper, TestAuthFixture }
import mesosphere.marathon.api.akkahttp.AuthDirectives.{ NotAuthenticated, NotAuthorized }
import mesosphere.marathon.api.akkahttp.Rejections.EntityNotFound
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.storage.repository.RuntimeConfigurationRepository
import org.scalatest.Inside

import scala.concurrent.Future

class LeaderControllerTest extends UnitTest with ScalatestRouteTest with Inside {

  "LeaderResource" should {
    "return the leader info" in {
      Given("a leader has been elected")
      val f = new Fixture()
      val controller = f.leaderController()
      f.electionService.leaderHostPort returns (Some("new.leader.com"))

      When("we try to fetch the info")
      Get(Uri./) ~> controller.route ~> check {
        Then("we receive all info")
        status should be(StatusCodes.OK)
        val expected =
          """{
            |  "leader": "new.leader.com"
            |}""".stripMargin
        JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
      }
    }

    "return 404 if no leader has been elected" in {
      Given("no leader has been elected")
      val f = new Fixture()
      val controller = f.leaderController()
      f.electionService.leaderHostPort returns (None)

      When("we try to fetch the info")
      Get(Uri./) ~> controller.route ~> check {
        Then("we receive EntityNotFound response")
        rejection shouldBe an[EntityNotFound]
        inside(rejection) {
          case EntityNotFound(message) =>
            message.message should be("There is no leader")
        }
      }
    }

    "access without authentication is denied" in {
      Given("An unauthenticated request")
      val f = new Fixture(authenticated = false)
      val controller = f.leaderController()

      When("we try to get the leader info")
      Get(Uri./) ~> controller.route ~> check {
        Then("we receive a NotAuthenticated response")
        rejection shouldBe a[NotAuthenticated]
        inside(rejection) {
          case NotAuthenticated(response) =>
            response.status should be(StatusCodes.Forbidden)
        }
      }
    }

    "access without authorization is denied" in {
      Given("An unauthenticated request")
      val f = new Fixture(authorized = false)
      val controller = f.leaderController()

      When("we try to get the leader info")
      Get(Uri./) ~> controller.route ~> check {
        Then("we receive a NotAuthenticated response")
        rejection shouldBe a[NotAuthorized]
        inside(rejection) {
          case NotAuthorized(response) =>
            response.status should be(StatusCodes.Unauthorized)
        }
      }
    }

    "abdicate leadership" in {
      Given("the host is not leader")
      val f = new Fixture()
      val controller = f.leaderController()
      f.electionService.isLeader returns (true)
      f.runtimeRepo.store(raml.RuntimeConfiguration(Some("s3://mybucket/foo"), None)) returns (Future.successful(Done))

      When("we try to abdicate")
      Delete("/?backup=s3://mybucket/foo") ~> controller.route ~> check {
        Then("we abdicate")
        verify(f.electionService, once).abdicateLeadership()

        And("receive HTTP ok")
        status should be(StatusCodes.OK)
        val expected =
          """{
            |  "message": "Leadership abdicated"
            |}""".stripMargin
        JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
      }
    }

    "not abdicate leadership" in {
      Given("the host is not leader")
      val f = new Fixture()
      val controller = f.leaderController()
      f.electionService.isLeader returns (false)

      When("we try to abdicate")
      Delete(Uri./) ~> controller.route ~> check {
        Then("we receive EntityNotFound response")
        rejection shouldBe an[EntityNotFound]
        inside(rejection) {
          case EntityNotFound(message) =>
            message.message should be("There is no leader")
        }
      }
    }
  }

  class Fixture(authenticated: Boolean = true, authorized: Boolean = true) {
    val electionService = mock[ElectionService]
    val runtimeRepo = mock[RuntimeConfigurationRepository]

    val auth = new TestAuthFixture()
    auth.authenticated = authenticated
    auth.authorized = authorized
    implicit val authenticator = auth.auth

    val config = AllConf.withTestConfig()
    def leaderController() = new LeaderController(electionService, runtimeRepo)
  }
}
