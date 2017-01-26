package mesosphere.marathon
package core.event.impl.stream

import java.util.Collections
import javax.servlet.http.HttpServletRequest

import mesosphere.UnitTest
import mesosphere.marathon.stream._
import org.eclipse.jetty.servlets.EventSource.Emitter

class HttpEventSSEHandleTest extends UnitTest {
  "HttpEventSSEHandle" should {
    "events should be filtered" in {
      Given("An emiter")
      val emitter = mock[Emitter]
      Given("An request with params")
      val req = mock[HttpServletRequest]
      req.getParameterMap returns Map("event_type" -> Array("xyz"))

      Given("handler for request is created")
      val handle = new HttpEventSSEHandle(req, emitter)

      When("Want to sent unwanted event")
      handle.sendEvent("any event", "")

      Then("event should NOT be sent")
      verify(emitter, never).event("any event", "")

      When("Want to sent subscribed event")
      handle.sendEvent("xyz", "")

      Then("event should be sent")
      verify(emitter).event("xyz", "")
    }

    "events should NOT be filtered" in {
      Given("An emiter")
      val emitter = mock[Emitter]

      Given("An request without params")
      val req = mock[HttpServletRequest]
      req.getParameterMap returns Collections.emptyMap()

      Given("handler for request is created")
      val handle = new HttpEventSSEHandle(req, emitter)

      When("Want to sent event")
      handle.sendEvent("any event", "")
      Then("event should NOT be sent")

      verify(emitter).event("any event", "")
      When("Want to sent event")

      handle.sendEvent("xyz", "")

      Then("event should be sent")
      verify(emitter).event("xyz", "")
    }
  }
}
