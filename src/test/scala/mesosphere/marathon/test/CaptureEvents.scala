package mesosphere.marathon.test

import akka.actor.ActorDSL._
import akka.actor.{ ActorSystem, PoisonPill, Terminated }
import akka.event.EventStream
import akka.testkit.TestProbe
import mesosphere.marathon.core.event.MarathonEvent

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class CaptureEvents(eventStream: EventStream) {
  /**
    * Captures the events send to the EventStream while the block is executing.
    */
  def forBlock(block: => Unit): Seq[MarathonEvent] = {
    implicit val actorSystem = ActorSystem("captureEvents")

    // yes, this is ugly. Since we only access it in the actor until it terminates, we do have
    // the correct thread sync boundaries in place.

    var capture = Vector.empty[MarathonEvent]
    val captureEventsActor = actor {
      new Act {
        become {
          case captureMe: MarathonEvent => capture :+= captureMe
        }
      }
    }
    eventStream.subscribe(captureEventsActor, classOf[MarathonEvent])
    eventStream.subscribe(captureEventsActor, classOf[String])

    try {
      block
    } finally {
      eventStream.unsubscribe(captureEventsActor)
      captureEventsActor ! PoisonPill
      val probe = TestProbe()
      probe.watch(captureEventsActor)
      probe.expectMsgClass(classOf[Terminated])
      Await.result(actorSystem.terminate(), Duration.Inf)
    }

    capture
  }

}
