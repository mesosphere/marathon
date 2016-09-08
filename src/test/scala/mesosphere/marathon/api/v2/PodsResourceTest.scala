package mesosphere.marathon.api.v2

import javax.servlet.http.HttpServletResponse

import akka.event.EventStream
import akka.stream.Materializer
import mesosphere.marathon._
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.pod.PodManager
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.DeploymentPlan
import org.scalatest.Matchers

import scala.concurrent.Future

class PodsResourceTest extends MarathonSpec with Matchers with Mockito {

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
    val response = f.podsResource.create(
      """
        | { "id": "/mypod", "containers": [
        |   { "name": "webapp",
        |     "resources": { "cpus": 0.03, "mem": 64 },
        |     "image": { "kind": "DOCKER", "id": "busybox" },
        |     "exec": { "command": { "shell": "sleep 1" } } } ] }
      """.stripMargin.getBytes(), false, f.auth.request)

    response.getStatus() should be(HttpServletResponse.SC_CREATED)
    // TODO(jdef) body should be that of the pod that was just created
    // TODO(jdef) deployment plan header should be set
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
