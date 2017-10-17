package mesosphere.marathon
package core.event.impl.stream

import java.util.Collections
import javax.servlet.http.HttpServletRequest

import mesosphere.UnitTest
import mesosphere.marathon.core.event.{ Subscribe, Unsubscribe }
import mesosphere.marathon.stream.Implicits._
import org.eclipse.jetty.servlets.EventSource.Emitter

class HttpEventSSEHandleTest extends UnitTest {
  "HttpEventSSEHandle" should {
    "events should be filtered" in {
      Given("An emitter")
      val emitter = mock[Emitter]
      Given("An request with params")
      val req = mock[HttpServletRequest]
      req.getParameterMap returns Map("event_type" -> Array(unsubscribe.eventType)).asJava

      Given("handler for request is created")
      val handle = new HttpEventSSEHandle(req, emitter)

      When("Want to sent unwanted event")
      handle.sendEvent(subscribed)

      Then("event should NOT be sent")
      verify(emitter, never).event(eq(subscribed.eventType), any[String])

      When("Want to sent subscribed event")
      handle.sendEvent(unsubscribe)

      Then("event should be sent")
      verify(emitter).event(eq(unsubscribe.eventType), any[String])
    }

    "events should NOT be filtered" in {
      Given("An emitter")
      val emitter = mock[Emitter]

      Given("An request without params")
      val req = mock[HttpServletRequest]
      req.getParameterMap returns Collections.emptyMap()

      Given("handler for request is created")
      val handle = new HttpEventSSEHandle(req, emitter)

      When("Want to sent event")
      handle.sendEvent(subscribed)

      Then("event should be sent")
      verify(emitter).event(eq(subscribed.eventType), any[String])

      When("Want to sent event")
      handle.sendEvent(unsubscribe)

      Then("event should be sent")
      verify(emitter).event(eq(unsubscribe.eventType), any[String])
    }
  }

  val subscribed = Subscribe("client IP", "callback URL")
  val unsubscribe = Unsubscribe("client IP", "callback URL")
}
