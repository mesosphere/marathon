package mesosphere.marathon
package core.election.impl

import akka.actor.ActorRef
import akka.event.EventStream
import mesosphere.marathon.core.election.LocalLeadershipEvent

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
}
