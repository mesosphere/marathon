package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import mesosphere.UnitTest
import mesosphere.marathon.api.{ JsonTestHelper, TestAuthFixture }
import mesosphere.marathon.api.akkahttp.AuthDirectives.{ NotAuthenticated, NotAuthorized }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import mesosphere.util.state.{ FrameworkId, MesosLeaderInfo }
import org.rogach.scallop.ScallopConf
import org.scalatest.Inside

import scala.concurrent.Future

class InfoControllerTest extends UnitTest with ScalatestRouteTest with Inside {

  "InfoController" should {
    "return all info" in {
      Given("An authenticated request")
      val f = new Fixture()
      implicit val electionService = mock[ElectionService]
      implicit val authenticator = f.auth.auth

      electionService.leaderHostPort returns Some("80")
      f.frameworkIdRepository.get() returns Future.successful(Some(FrameworkId("foobar")))
      f.leaderInfo.currentLeaderUrl returns Some("leader")

      val controller = new InfoController(f.leaderInfo, f.frameworkIdRepository, f.config)

      When("we try to fetch the info")
      Get(Uri./) ~> controller.route ~> check {
        Then("we receive all info")
        status should be(StatusCodes.OK)
        val expected =
          """{
            |  "name" : "unknown",
            |  "version" : "1.5.0-SNAPSHOT",
            |  "buildref" : "unknown",
            |  "elected" : false,
            |  "leader" : "80",
            |  "frameworkId" : "foobar",
            |  "marathon_config" : {
            |    "master" : "foo",
            |    "failover_timeout" : 604800,
            |    "framework_name" : "marathon",
            |    "ha" : true,
            |    "checkpoint" : true,
            |    "local_port_min" : 10000,
            |    "local_port_max" : 20000,
            |    "executor" : "//cmd",
            |    "hostname" : "Karstens-MacBook-Pro.local",
            |    "task_launch_timeout" : 300000,
            |    "task_reservation_timeout" : 20000,
            |    "reconciliation_initial_delay" : 15000,
            |    "reconciliation_interval" : 600000,
            |    "mesos_user" : "kjeschkies",
            |    "leader_proxy_connection_timeout_ms" : 5000,
            |    "leader_proxy_read_timeout_ms" : 10000,
            |    "features" : [ ],
            |    "mesos_leader_ui_url" : "leader"
            |  },
            |  "zookeeper_config" : {
            |    "zk" : "zk://localhost:2181/marathon",
            |    "zk_timeout" : 10000,
            |    "zk_connection_timeout" : 10000,
            |    "zk_session_timeout" : 10000,
            |    "zk_max_versions" : 50
            |  },
            |  "http_config" : {
            |    "http_port" : 8080,
            |    "https_port" : 8081
            |  }
            |}""".stripMargin
        JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
      }
    }

    "deny access without authentication" in {
      Given("An unauthenticated request")
      val f = new Fixture(authenticated = false)
      implicit val electionService = mock[ElectionService]
      implicit val authenticator = f.auth.auth
      val controller = new InfoController(f.leaderInfo, f.frameworkIdRepository, f.config)

      When("we try to fetch the info")
      Get(Uri./) ~> controller.route ~> check {
        Then("we receive a NotAuthenticated response")
        rejection shouldBe a[NotAuthenticated]
        inside(rejection) {
          case NotAuthenticated(response) =>
            response.status should be(StatusCodes.Forbidden)
        }
      }
    }

    "deny without authorization" in {
      Given("An unauthorized request")
      val f = new Fixture(authorized = false)
      implicit val electionService = mock[ElectionService]
      implicit val authenticator = f.auth.auth
      val controller = new InfoController(f.leaderInfo, f.frameworkIdRepository, f.config)

      When("we try to fetch the info")
      Get(Uri./) ~> controller.route ~> check {
        Then("we receive a NotAuthenticated response")
        rejection shouldBe a[NotAuthorized]
        inside(rejection) {
          case NotAuthorized(response) =>
            response.status should be(StatusCodes.Unauthorized)
        }
      }
    }
  }

  class Fixture(authenticated: Boolean = true, authorized: Boolean = true) {
    val config: MarathonConf = new ScallopConf(Seq("--master", "foo")) with MarathonConf {
      verify()
    }
    val auth = new TestAuthFixture()
    auth.authenticated = authenticated
    auth.authorized = authorized

    val frameworkIdRepository = mock[FrameworkIdRepository]
    val leaderInfo = mock[MesosLeaderInfo]
  }
}
