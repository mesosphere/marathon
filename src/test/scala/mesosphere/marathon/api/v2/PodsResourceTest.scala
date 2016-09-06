package mesosphere.marathon.api.v2

import javax.servlet.http.HttpServletResponse

import akka.event.EventStream
import mesosphere.marathon._
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.base.{ Clock, ConstantClock }
import mesosphere.marathon.core.pod.PodManager
import mesosphere.marathon.test.Mockito
import org.scalatest.Matchers

class PodsResourceTest extends MarathonSpec with Matchers with Mockito {

  test("Marathon supports pods") {
    val f = Fixture.create()
    val response = f.podsResource.capability(f.auth.request)
    response.getStatus should be(HttpServletResponse.SC_OK)

    val body = Option(response.getEntity.asInstanceOf[String])
    body should be(None)
  }

  case class Fixture(
    podsResource: PodsResource,
    auth: TestAuthFixture
  )

  object Fixture {
    def create(
      configArgs: Seq[String] = Seq.empty[String],
      auth: TestAuthFixture = new TestAuthFixture()
    )(implicit
      podSystem: PodManager = mock[PodManager],
      clock: Clock = ConstantClock(),
      eventBus: EventStream = mock[EventStream]): Fixture = {
      val config = AllConf.withTestConfig(configArgs: _*)
      new Fixture(
        new PodsResource(config, auth.auth, auth.auth),
        auth
      )
    }
  }
}
