package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.api.{ JsonTestHelper, TestAuthFixture }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.storage.repository.FrameworkIdRepository
import mesosphere.util.state.{ FrameworkId, MesosLeaderInfo }
import org.rogach.scallop.ScallopConf
import org.scalatest.Inside

import scala.concurrent.Future

class InfoControllerTest extends UnitTest with ScalatestRouteTest with Inside with RouteBehaviours {

  "InfoController" should {
    "return all info" in {
      Given("An authenticated request")
      val f = Fixture()

      f.electionService.leaderHostPort returns Some("80")
      f.frameworkIdRepository.get() returns Future.successful(Some(FrameworkId("foobar")))
      f.leaderInfo.currentLeaderUrl returns Some("leader")

      val controller = f.controller()

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
            |    "access_control_allow_origin" : [ ],
            |    "checkpoint" : true,
            |    "decline_offer_duration" : 120000,
            |    "executor" : "//cmd",
            |    "failover_timeout" : 604800,
            |    "features" : [ ],
            |    "framework_name" : "marathon",
            |    "ha" : true,
            |    "hostname" : "heart.of.gold",
            |    "launch_token" : 100,
            |    "launch_token_refresh_interval" : 30000,
            |    "leader_proxy_connection_timeout_ms" : 5000,
            |    "leader_proxy_read_timeout_ms" : 10000,
            |    "local_port_max" : 20000,
            |    "local_port_min" : 10000,
            |    "master" : "foo",
            |    "max_instances_per_offer" : 5,
            |    "mesos_bridge_name" : "mesos-bridge",
            |    "mesos_heartbeat_failure_threshold" : 5,
            |    "mesos_heartbeat_interval" : 15000,
            |    "mesos_leader_ui_url" : "leader",
            |    "mesos_user" : "Adam Douglas",
            |    "min_revive_offers_interval" : 5000,
            |    "offer_matching_timeout" : 3000,
            |    "on_elected_prepare_timeout" : 180000,
            |    "reconciliation_initial_delay" : 15000,
            |    "reconciliation_interval" : 600000,
            |    "revive_offers_for_new_apps" : true,
            |    "revive_offers_repetitions" : 3,
            |    "scale_apps_initial_delay" : 15000,
            |    "scale_apps_interval" : 300000,
            |    "store_cache" : true,
            |    "task_launch_confirm_timeout" : 300000,
            |    "task_launch_timeout" : 300000,
            |    "task_lost_expunge_initial_delay" : 300000,
            |    "task_lost_expunge_interval" : 30000,
            |    "task_reservation_timeout" : 20000
            |  },
            |  "zookeeper_config" : {
            |    "zk" : "zk://localhost:2181/marathon",
            |    "zk_compression" : true,
            |    "zk_compression_threshold" : 65536,
            |    "zk_connection_timeout" : 10000,
            |    "zk_max_node_size" : 1024000,
            |    "zk_max_versions" : 50,
            |    "zk_session_timeout" : 10000,
            |    "zk_timeout" : 10000
            |  },
            |  "http_config" : {
            |    "http_port" : 8080,
            |    "https_port" : 8081
            |  }
            |}""".stripMargin
        JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
      }
    }

    {
      val controller = Fixture(authenticated = false).controller()
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Get(Uri./))
    }

    {
      val controller = Fixture(authorized = false).controller()
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = Get(Uri./))
    }

  }

  case class Fixture(authenticated: Boolean = true, authorized: Boolean = true) {
    val options = Seq(
      "--master", "foo",
      "--mesos_user", "Adam Douglas",
      "--hostname", "heart.of.gold",
      "--http_port", "8080",
      "--https_port", "8081"
    )
    val config = new ScallopConf(options) with MarathonConf with HttpConf {
      verify()
    }
    val auth = new TestAuthFixture()
    auth.authenticated = authenticated
    auth.authorized = authorized

    val frameworkIdRepository = mock[FrameworkIdRepository]
    val leaderInfo = mock[MesosLeaderInfo]

    implicit val electionService = mock[ElectionService]
    implicit val authenticator = auth.auth
    def controller() = new InfoController(leaderInfo, frameworkIdRepository, config)
  }
}
