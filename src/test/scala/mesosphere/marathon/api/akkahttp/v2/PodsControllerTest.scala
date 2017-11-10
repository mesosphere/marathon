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
import org.scalatest.matchers.{ HavePropertyMatchResult, HavePropertyMatcher }
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

    def executorResources(cpus: Double, mem: Double, disk: Double) = new HavePropertyMatcher[JsValue, Option[JsValue]] {
      override def apply(actual: JsValue) = {
        val maybeActual = (actual \ "executorResources").toOption
        val expected = JsObject(Seq("cpus" -> JsNumber(cpus), "mem" -> JsNumber(mem), "disk" -> JsNumber(disk)))
        val matches = maybeActual.contains(expected)
        HavePropertyMatchResult(matches, "executorResources", Some(expected), maybeActual)
      }
    }
    val noDefinedNetworkname = new HavePropertyMatcher[JsValue, Option[JsValue]] {
      override def apply(actual: JsValue) = {
        val actualNetworkname = (actual \ "networks" \ 0 \ "name").toOption
        val matches = !actualNetworkname.isDefined
        HavePropertyMatchResult(matches, "networkname", None, actualNetworkname)
      }
    }
    def networkMode(mode: raml.NetworkMode) = new HavePropertyMatcher[JsValue, Option[String]] {
      override def apply(actual: JsValue) = {
        val maybeMode = (actual \ "networks" \ 0 \ "mode").asOpt[String]
        val matches = maybeMode.contains(mode.value)
        HavePropertyMatchResult(matches, "networkmode", Some(mode.value), maybeMode)
      }
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

        val jsonResponse: JsValue = Json.parse(responseAs[String])

        jsonResponse should have (
          executorResources(cpus = 0.1, mem = 32.0, disk = 10.0),
          noDefinedNetworkname,
          networkMode(raml.NetworkMode.Host)
        )
      }
    }

    "handle normalization errors" in {
      val f = Fixture()
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJsonWithUnresolvableNetworkName =
        """
          | { "id": "/mypod", "networks": [ { "mode": "container" } ], "containers": [
          |   { "name": "webapp",
          |     "resources": { "cpus": 0.03, "mem": 64 },
          |     "image": { "kind": "DOCKER", "id": "busybox" },
          |     "exec": { "command": { "shell": "sleep 1" } } } ] }
        """.stripMargin
      val entity = HttpEntity(podSpecJsonWithUnresolvableNetworkName).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))

      request ~> controller.route ~> check {
        response.status should be(StatusCodes.UnprocessableEntity)
      }
    }

    "be able to create a simple single-container pod with bridge network" in {
      val f = Fixture(configArgs = Seq("--default_network_name", "blah"))
      val controller = f.controller()

      val deploymentPlan = DeploymentPlan.empty
      f.podManager.create(any, eq(false)).returns(Future.successful(deploymentPlan))

      val podSpecJsonWithBridgeNetwork = """
                                           | { "id": "/mypod", "networks": [ { "mode": "container/bridge" } ], "containers": [
                                           |   { "name": "webapp",
                                           |     "resources": { "cpus": 0.03, "mem": 64 },
                                           |     "image": { "kind": "DOCKER", "id": "busybox" },
                                           |     "exec": { "command": { "shell": "sleep 1" } } } ] }
                                         """.stripMargin
      val entity = HttpEntity(podSpecJsonWithBridgeNetwork).withContentType(ContentTypes.`application/json`)
      val request = Post(Uri./.withQuery(Query("force" -> "false")))
        .withEntity(entity)
        .withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12"))))

      request ~> controller.route ~> check {
        response.status should be(StatusCodes.Created)
        response.header[Headers.`Marathon-Deployment-Id`].value.value() should be(deploymentPlan.id)
        response.header[Location].value.value() should be("/mypod")

        val jsonResponse = Json.parse(responseAs[String])

        jsonResponse should have (
          executorResources (cpus = 0.1, mem = 32.0, disk = 10.0),
          noDefinedNetworkname,
          networkMode(raml.NetworkMode.ContainerBridge)
        )
      }
    }

    //    "The secrets feature is NOT enabled and create pod (that uses file base secrets) fails" in {
    //      implicit val podSystem = mock[PodManager]
    //      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec
    //
    //      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
    //
    //      val response = f.podsResource.create(podSpecJsonWithFileBasedSecret.getBytes(), force = false, f.auth.request)
    //
    //      withClue(s"response body: ${response.getEntity}") {
    //        response.getStatus should be(422)
    //        response.getEntity.toString should include("Feature secrets is not enabled")
    //      }
    //    }

    //    "The secrets feature is NOT enabled and create pod (that uses env secret refs) fails" in {
    //      implicit val podSystem = mock[PodManager]
    //      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec
    //
    //      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
    //
    //      val response = f.podsResource.create(podSpecJsonWithEnvRefSecret.getBytes(), force = false, f.auth.request)
    //
    //      withClue(s"response body: ${response.getEntity}") {
    //        response.getStatus should be(422)
    //        response.getEntity.toString should include("Feature secrets is not enabled")
    //      }
    //    }

    //    "The secrets feature is NOT enabled and create pod (that uses env secret refs on container level) fails" in {
    //      implicit val podSystem = mock[PodManager]
    //      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // should not be injected into host network spec
    //
    //      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
    //
    //      val response = f.podsResource.create(podSpecJsonWithEnvRefSecretOnContainerLevel.getBytes(), force = false, f.auth.request)
    //
    //      withClue(s"response body: ${response.getEntity}") {
    //        response.getStatus should be(422)
    //        response.getEntity.toString should include("Feature secrets is not enabled")
    //      }
    //    }

    //    "The secrets feature is enabled and create pod (that uses env secret refs on container level) succeeds" in {
    //      implicit val podSystem = mock[PodManager]
    //      val f = Fixture(configArgs = Seq("--default_network_name", "blah", "--enable_features", Features.SECRETS)) // should not be injected into host network spec
    //
    //      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
    //
    //      val response = f.podsResource.create(podSpecJsonWithEnvRefSecretOnContainerLevel.getBytes(), force = false, f.auth.request)
    //
    //      withClue(s"response body: ${response.getEntity}") {
    //        response.getStatus should be(201)
    //        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
    //        parsedResponse should be (defined)
    //        val maybePod = parsedResponse.map(_.as[Pod])
    //        maybePod should be (defined) // validate that we DID get back a pod definition
    //        val pod = maybePod.get
    //        pod.containers(0).environment("vol") shouldBe EnvVarSecret("secret1")
    //      }
    //    }

    //    "The secrets feature is enabled and create pod (that uses file based secrets) succeeds" in {
    //      implicit val podSystem = mock[PodManager]
    //      val f = Fixture(configArgs = Seq("--default_network_name", "blah", "--enable_features", Features.SECRETS)) // should not be injected into host network spec
    //
    //      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
    //
    //      val response = f.podsResource.create(podSpecJsonWithFileBasedSecret.getBytes(), force = false, f.auth.request)
    //
    //      withClue(s"response body: ${response.getEntity}") {
    //        response.getStatus should be(201)
    //        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
    //        parsedResponse should be (defined)
    //        val maybePod = parsedResponse.map(_.as[Pod])
    //        maybePod should be (defined) // validate that we DID get back a pod definition
    //        val pod = maybePod.get
    //        pod.volumes(0) shouldBe PodSecretVolume("vol", "secret1")
    //      }
    //    }

    //    "create a pod w/ container networking" in {
    //      implicit val podSystem = mock[PodManager]
    //      val f = Fixture(configArgs = Seq("--default_network_name", "blah")) // required since network name is missing from JSON
    //
    //      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
    //
    //      val response = f.podsResource.create(podSpecJsonWithContainerNetworking.getBytes(), force = false, f.auth.request)
    //
    //      withClue(s"response body: ${response.getEntity}") {
    //        response.getStatus should be(HttpServletResponse.SC_CREATED)
    //
    //        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
    //        parsedResponse should be (defined)
    //        val maybePod = parsedResponse.map(_.as[Pod])
    //        maybePod should be (defined) // validate that we DID get back a pod definition
    //        val pod = maybePod.get
    //        pod.networks(0).mode should be (NetworkMode.Container)
    //        pod.networks(0).name should be (Some("blah"))
    //        pod.executorResources should be (defined) // validate that executor resources are defined
    //        pod.executorResources.get should be (ExecutorResources()) // validate that the executor resources has default values
    //
    //        response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    //      }
    //    }

    //    "create a pod w/ container networking w/o default network name" in {
    //      implicit val podSystem = mock[PodManager]
    //      val f = Fixture()
    //
    //      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
    //
    //      val response = f.podsResource.create(podSpecJsonWithContainerNetworking.getBytes(), force = false, f.auth.request)
    //      response.getStatus shouldBe 422
    //      response.getEntity.toString should include(NetworkValidationMessages.NetworkNameMustBeSpecified)
    //    }

    //    "create a pod with custom executor resource declaration" in {
    //      implicit val podSystem = mock[PodManager]
    //      val f = Fixture()
    //
    //      podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
    //
    //      val response = f.podsResource.create(podSpecJsonWithExecutorResources.getBytes(), force = false, f.auth.request)
    //
    //      withClue(s"response body: ${response.getEntity}") {
    //        response.getStatus should be(HttpServletResponse.SC_CREATED)
    //
    //        val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
    //        parsedResponse should be (defined)
    //        val maybePod = parsedResponse.map(_.as[Pod])
    //        maybePod should be (defined) // validate that we DID get back a pod definition
    //        val pod = maybePod.get
    //        pod.executorResources should be (defined) // validate that executor resources are defined
    //        pod.executorResources.get.cpus should be (100)
    //        pod.executorResources.get.mem should be (100)
    //        // disk is not assigned in the posted pod definition, therefore this should be the default value 10
    //        pod.executorResources.get.disk should be (10)
    //
    //        response.getMetadata.containsKey(RestResource.DeploymentHeader) should be(true)
    //      }
    //    }
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
