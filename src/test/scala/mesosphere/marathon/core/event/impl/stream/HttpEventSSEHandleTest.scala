package mesosphere.marathon
package core.event.impl.stream

import java.util.Collections
import javax.servlet.http.HttpServletRequest
import mesosphere.UnitTest
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.event.{DeploymentSuccess, Subscribe, Unsubscribe}
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.GroupCreation
import org.eclipse.jetty.servlets.EventSource.Emitter
import org.mockito.ArgumentCaptor

class HttpEventSSEHandleTest extends UnitTest with GroupCreation {
  val metrics = DummyMetrics

  "HttpEventSSEHandle" should {
    "events should be filtered" in {
      Given("An emitter")
      val emitter = mock[Emitter]
      Given("An request with params")
      val req = mock[HttpServletRequest]
      req.getParameterMap returns Map("event_type" -> Array(unsubscribe.eventType)).asJava

      Given("handler for request is created")
      val handle = new HttpEventSSEHandle(metrics, req, emitter)

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
      val handle = new HttpEventSSEHandle(metrics, req, emitter)

      When("Want to sent event")
      handle.sendEvent(subscribed)

      Then("event should be sent")
      verify(emitter).event(eq(subscribed.eventType), any[String])

      When("Want to sent event")
      handle.sendEvent(unsubscribe)

      Then("event should be sent")
      verify(emitter).event(eq(unsubscribe.eventType), any[String])
    }

    "events should be sent" in {
      val captor = ArgumentCaptor.forClass(classOf[String])
      Given("An emitter")
      val emitter = mock[Emitter]

      Given("A request without params")
      val req = mock[HttpServletRequest]
      req.getParameterMap returns Collections.emptyMap()

      Given("handler for request is created")
      val handle = new HttpEventSSEHandle(metrics, req, emitter)

      When("Want to sent event")
      handle.sendEvent(deployed)

      Then("event should be sent")
      verify(emitter).event(eq(deployed.eventType), captor.capture())
      captor.getValue shouldBe deployed.jsonString
    }
  }

  val app = AppDefinition("app".toRootPath, cmd = Some("sleep"))
  val oldGroup = createRootGroup()
  val newGroup = createRootGroup(Map(app.id -> app))
  val plan = DeploymentPlan(oldGroup, newGroup)
  val deployed: DeploymentSuccess = DeploymentSuccess(
    id = "test-deployment",
    plan,
    timestamp = "2018-01-01T00:00:00.000Z")

  val subscribed = Subscribe("client IP", "callback URL")
  val unsubscribe = Unsubscribe("client IP", "callback URL")
}
