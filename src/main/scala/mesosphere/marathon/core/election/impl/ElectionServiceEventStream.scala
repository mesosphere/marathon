package mesosphere.marathon
package core.election.impl

import akka.actor.{ ActorRef, Cancellable, PoisonPill }
import akka.event.EventStream
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Keep, Source }
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.election.LocalLeadershipEvent
import mesosphere.marathon.util.CancellableOnce

private[impl] trait ElectionServiceEventStream {
  protected val eventStream: EventStream

  def isLeader: Boolean

  def subscribe(subscriber: ActorRef): Unit = {
    eventStream.subscribe(subscriber, classOf[LocalLeadershipEvent])
    val currentState = if (isLeader) LocalLeadershipEvent.ElectedAsLeader else LocalLeadershipEvent.Standby
    subscriber ! currentState
  }

  def unsubscribe(subscriber: ActorRef): Unit = {
    eventStream.unsubscribe(subscriber, classOf[LocalLeadershipEvent])
  }

  val localLeadershipEvents: Source[LocalLeadershipEvent, Cancellable] = {
    Source.actorRef[LocalLeadershipEvent](16, OverflowStrategy.dropHead) // drop older elements
      .watchTermination()(Keep.both)
      .mapMaterializedValue {
        case (ref, terminated) =>
          subscribe(ref)
          // If the stream terminates, for any reason, then unsubscribe from the event stream
          terminated.onComplete(_ => unsubscribe(ref))(ExecutionContexts.callerThread)
          // If the stream cancellable gets called, kill the actor created by Source.actorRef; this will gracefully
          // terminate the stream
          new CancellableOnce(() => ref ! PoisonPill)
      }
  }

}
