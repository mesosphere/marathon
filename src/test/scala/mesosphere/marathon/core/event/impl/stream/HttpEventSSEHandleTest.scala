package mesosphere.marathon.core.event.impl.stream

import javax.servlet.http.HttpServletRequest

import mesosphere.marathon.test.{ MarathonSpec, Mockito }
import org.eclipse.jetty.servlets.EventSource.Emitter
import org.scalatest.{ GivenWhenThen, Matchers }
import collection.JavaConversions._

class HttpEventSSEHandleTest extends MarathonSpec with Matchers with Mockito with GivenWhenThen {

  test("events should be filtered") {
    Given("An emiter")
    val emitter = mock[Emitter]
    Given("An request with params")
    val req = mock[HttpServletRequest]
    req.getParameterMap returns mapAsJavaMap(Map("event_type" -> Array("xyz")))

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

  test("events should NOT be filtered") {
    Given("An emiter")
    val emitter = mock[Emitter]

    Given("An request without params")
    val req = mock[HttpServletRequest]
    req.getParameterMap returns mapAsJavaMap(Map.empty)

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

