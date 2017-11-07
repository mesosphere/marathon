package mesosphere.marathon
package api.akkahttp.v2

import java.net.InetAddress

import akka.event.EventStream
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ Location, `Remote-Address` }
import mesosphere.UnitTest
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.api.akkahttp.Headers
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.pod.PodManager
import mesosphere.marathon.test.SettableClock

import play.api.libs.json._
import play.api.libs.json.Json

import scala.concurrent.Future

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
      val podSpecJson = """
                          | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers": [
                          |   { "name": "webapp",
                          |     "resources": { "cpus": 0.03, "mem": 64 },
                          |     "image": { "kind": "DOCKER", "id": "busybox" },
                          |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                        """.stripMargin
      val entity = HttpEntity(podSpecJson).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))
      behave like unauthorizedRoute(forRoute = controller.route, withRequest = request)
    }

    "be able to create a simple single-container pod from docker image w/ shell command" in {
      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJson = """
                          | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers": [
                          |   { "name": "webapp",
                          |     "resources": { "cpus": 0.03, "mem": 64 },
                          |     "image": { "kind": "DOCKER", "id": "busybox" },
                          |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                        """.stripMargin
      val entity = HttpEntity(podSpecJson).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))
      request ~> controller.route ~> check {
        response.status should be(StatusCodes.Created)
        response.header[Headers.`Marathon-Deployment-Id`].value.value() should be(deploymentPlan.id)
        response.header[Location].value.value() should be("/mypod")

        (Json.parse(responseAs[String]) \ "networks" \ 0 \ "mode") shouldBe JsDefined(JsString(raml.NetworkMode.Host.value))
        (Json.parse(responseAs[String]) \ "networks" \ 0 \ "name").isDefined should be(false)

        (Json.parse(responseAs[String]) \ "executorResources" \ "cpus") shouldBe JsDefined(JsNumber(0.1))
        (Json.parse(responseAs[String]) \ "executorResources" \ "mem") shouldBe JsDefined(JsNumber(32.0))
        (Json.parse(responseAs[String]) \ "executorResources" \ "disk") shouldBe JsDefined(JsNumber(10.0))
      }
    }
  }

  case class Fixture(
      configArgs: Seq[String] = Seq.empty[String],
      authenticated: Boolean = true,
      authorized: Boolean = true,
      isLeader: Boolean = true) {
    val config = AllConf.withTestConfig(configArgs: _*)
    val clock = new SettableClock

    val auth = new TestAuthFixture()
    auth.authenticated = authenticated
    auth.authorized = authorized

    val electionService = mock[ElectionService]
    val groupManager = mock[GroupManager]
    val podManager = mock[PodManager]
    val pluginManager = PluginManager.None
    val eventBus = mock[EventStream]

    electionService.isLeader returns (isLeader)

    implicit val authenticator = auth.auth
    def controller() = new PodsController(config, electionService, podManager, groupManager, pluginManager, eventBus, clock)
  }
}
