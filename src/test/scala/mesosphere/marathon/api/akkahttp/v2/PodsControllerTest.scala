package mesosphere.marathon
package api.akkahttp.v2

import akka.event.EventStream
import akka.http.scaladsl.model.{StatusCodes, Uri}
import mesosphere.UnitTest
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.PodManager
import mesosphere.marathon.test.SettableClock

class PodsControllerTest extends UnitTest with ScalatestRouteTest with RouteBehaviours {

  "PodsController" should {
    "support pods" in {
      val controller = Fixture().controller()
      Head(Uri./) ~> controller.route ~> check {
        response.status should be(StatusCodes.OK)
        responseAs[String] shouldBe empty
      }
    }

    {
      val controller = Fixture(authenticated = false).controller()
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Head(Uri./))
      behave like unauthenticatedRoute(forRoute = controller.route, withRequest = Post(Uri./))
    }

    {
      val controller = Fixture(authorized = false).controller()
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = Post(Uri./))
    }
  }

  case class Fixture(authenticated: Boolean = true, authorized: Boolean = true, isLeader: Boolean = true) {
    val config = AllConf.withTestConfig()
    val clock = new SettableClock

    val auth = new TestAuthFixture()
    auth.authenticated = authenticated
    auth.authorized = authorized

    val electionService = mock[ElectionService]
    val groupManager = mock[GroupManager]
    val podManager = mock[PodManager]
    val pluginManager = mock[PluginManager]
    val eventBus = mock[EventStream]

    electionService.isLeader returns (isLeader)

    implicit val authenticator = auth.auth
    def controller() = new PodsController(config, electionService, podManager, groupManager, pluginManager, eventBus, clock)
  }
}
