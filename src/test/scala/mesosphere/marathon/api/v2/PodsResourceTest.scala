package mesosphere.marathon.api.v2

import javax.servlet.http.HttpServletResponse

import akka.event.EventStream
import akka.stream.Materializer
import mesosphere.marathon._
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.pod.{ PodDefinition, PodManager }
import mesosphere.marathon.raml.Pod
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.DeploymentPlan
import org.scalatest.Matchers
import play.api.libs.json._

import scala.concurrent.Future

class PodsResourceTest extends MarathonSpec with Matchers with Mockito {

  // TODO(jdef) test findAll
  // TODO(jdef) test status
  // TODO(jdef) incorporate checks for firing pod events on C, U, D operations

  test("Marathon supports pods") {
    val f = Fixture.create()
    val response = f.podsResource.capability(f.auth.request)
    response.getStatus should be(HttpServletResponse.SC_OK)

    val body = Option(response.getEntity.asInstanceOf[String])
    body should be(None)
  }

  test("create a simple single-container pod from docker image w/ shell command") {
    implicit val podSystem = mock[PodManager]
    val f = Fixture.create()

    podSystem.create(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

    val postJson = """
        | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers": [
        |   { "name": "webapp",
        |     "resources": { "cpus": 0.03, "mem": 64 },
        |     "image": { "kind": "DOCKER", "id": "busybox" },
        |     "exec": { "command": { "shell": "sleep 1" } } } ] }
      """.stripMargin
    val response = f.podsResource.create(postJson.getBytes(), false, f.auth.request)

    withClue(s"response body: ${response.getEntity}") {
      response.getStatus() should be(HttpServletResponse.SC_CREATED)

      val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
      parsedResponse should not be (None)
      parsedResponse.map(_.as[Pod]) should not be (None) // validate that we DID get back a pod definition

      response.getMetadata().containsKey(PodsResource.DeploymentHeader) should be(true)
    }
  }

  test("update a simple single-container pod from docker image w/ shell command") {
    implicit val podSystem = mock[PodManager]
    val f = Fixture.create()

    podSystem.update(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))

    val postJson = """
        | { "id": "/mypod", "networks": [ { "mode": "host" } ], "containers": [
        |   { "name": "webapp",
        |     "resources": { "cpus": 0.03, "mem": 64 },
        |     "image": { "kind": "DOCKER", "id": "busybox" },
        |     "exec": { "command": { "shell": "sleep 1" } } } ] }
      """.stripMargin
    val response = f.podsResource.update("/mypod", postJson.getBytes(), false, f.auth.request)

    withClue(s"response body: ${response.getEntity}") {
      response.getStatus() should be(HttpServletResponse.SC_OK)

      val parsedResponse = Option(response.getEntity.asInstanceOf[String]).map(Json.parse)
      parsedResponse should not be (None)
      parsedResponse.map(_.as[Pod]) should not be (None) // validate that we DID get back a pod definition

      response.getMetadata().containsKey(PodsResource.DeploymentHeader) should be(true)
    }
  }

  test("delete a pod") {
    implicit val podSystem = mock[PodManager]
    val f = Fixture.create()

    podSystem.find(any).returns(Future.successful(Some(PodDefinition())))
    podSystem.delete(any, eq(false)).returns(Future.successful(DeploymentPlan.empty))
    val response = f.podsResource.remove("/mypod", false, f.auth.request)

    withClue(s"response body: ${response.getEntity}") {
      response.getStatus() should be(HttpServletResponse.SC_ACCEPTED)

      val body = Option(response.getEntity.asInstanceOf[String])
      body should be(None)

      response.getMetadata().containsKey(PodsResource.DeploymentHeader) should be(true)
    }
  }

  test("lookup a specific pod, and that pod does not exist") {
    implicit val podSystem = mock[PodManager]
    val f = Fixture.create()

    podSystem.find(any).returns(Future.successful(Option.empty[PodDefinition]))
    val response = f.podsResource.find("/mypod", f.auth.request)

    withClue(s"response body: ${response.getEntity}") {
      response.getStatus() should be(HttpServletResponse.SC_NOT_FOUND)
      val body = Option(response.getEntity.asInstanceOf[String])
      body should not be (None)
      body.foreach(_ should include("mypod does not exist"))
    }
  }

  case class Fixture(
    podsResource: PodsResource,
    auth: TestAuthFixture,
    podSystem: PodManager
  )

  object Fixture {
    def create(
      configArgs: Seq[String] = Seq.empty[String],
      auth: TestAuthFixture = new TestAuthFixture()
    )(implicit
      podSystem: PodManager = mock[PodManager],
      eventBus: EventStream = mock[EventStream],
      mat: Materializer = mock[Materializer]): Fixture = {
      val config = AllConf.withTestConfig(configArgs: _*)
      new Fixture(
        new PodsResource(config, auth.auth, auth.auth),
        auth,
        podSystem
      )
    }
  }
}
