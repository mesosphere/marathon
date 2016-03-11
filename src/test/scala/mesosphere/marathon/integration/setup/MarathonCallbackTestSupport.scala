package mesosphere.marathon.integration.setup

import java.util.concurrent.ConcurrentLinkedQueue

import mesosphere.marathon.integration.facades.{ ITDeploymentResult, MarathonFacade }

import scala.annotation.tailrec
import scala.concurrent.duration.{ FiniteDuration, _ }

/**
  * Provides a Marathon callback test endpoint for integration tests.
  */
trait MarathonCallbackTestSupport extends ExternalMarathonIntegrationTest {
  def config: IntegrationTestConfig
  def marathon: MarathonFacade

  val events = new ConcurrentLinkedQueue[CallbackEvent]()

  protected def startCallbackEndpoint(httpPort: Int, cwd: String): Unit = {
    ProcessKeeper.startHttpService(httpPort, cwd)
    ExternalMarathonIntegrationTest.listener += this
    val callbackUrl: String = s"http://localhost:$httpPort/callback"
    ProcessKeeper.onStopServices {
      ExternalMarathonIntegrationTest.listener -= this
      marathon.unsubscribe(callbackUrl)
    }
    marathon.subscribe(callbackUrl)
  }

  override def handleEvent(event: CallbackEvent): Unit = events.add(event)

  def waitForEvent(kind: String, maxWait: FiniteDuration = 30.seconds): CallbackEvent = waitForEventWith(kind, _ => true, maxWait)

  def waitForDeploymentId(deploymentId: String, maxWait: FiniteDuration = 30.seconds): CallbackEvent = {
    waitForEventWith("deployment_success", _.info.getOrElse("id", "") == deploymentId, maxWait)
  }

  def waitForChange(change: RestResult[ITDeploymentResult], maxWait: FiniteDuration = 30.seconds): CallbackEvent = {
    waitForDeploymentId(change.value.deploymentId, maxWait)
  }

  def waitForEventMatching(description: String, maxWait: FiniteDuration = 30.seconds)(fn: CallbackEvent => Boolean): CallbackEvent = {
    @tailrec
    def nextEvent: Option[CallbackEvent] = if (events.isEmpty) None else {
      val event = events.poll()
      if (fn(event)) Some(event) else nextEvent
    }
    WaitTestSupport.waitFor(description, maxWait)(nextEvent)
  }

  def waitForEventWith(kind: String, fn: CallbackEvent => Boolean, maxWait: FiniteDuration = 30.seconds): CallbackEvent = {
    waitForEventMatching(s"event $kind to arrive", maxWait) { event =>
      event.eventType == kind && fn(event)
    }
  }

  def waitForStatusUpdates(kinds: String*) = kinds.foreach { kind =>
    waitForEventWith("status_update_event", _.info("taskStatus") == kind)
  }

  /**
    * Wait for the events of the given kinds (=types).
    */
  def waitForEvents(kinds: String*)(maxWait: FiniteDuration = 30.seconds): Map[String, Seq[CallbackEvent]] = {

    val deadline = maxWait.fromNow

    /** Receive the events for the given kinds (duplicates allowed) in any order. */
    val receivedEventsForKinds: Seq[CallbackEvent] = {
      var eventsToWaitFor = kinds
      val receivedEvents = Vector.newBuilder[CallbackEvent]

      while (eventsToWaitFor.nonEmpty) {
        val event = waitForEventMatching(s"event $eventsToWaitFor to arrive", deadline.timeLeft) { event =>
          eventsToWaitFor.contains(event.eventType)
        }
        receivedEvents += event

        // Remove received event kind. Only remove one element for duplicates.
        val kindIndex = eventsToWaitFor.indexWhere(_ == event.eventType)
        assert(kindIndex >= 0)
        eventsToWaitFor = eventsToWaitFor.patch(kindIndex, Nil, 1)
      }

      receivedEvents.result()
    }

    receivedEventsForKinds.groupBy(_.eventType)
  }
}
