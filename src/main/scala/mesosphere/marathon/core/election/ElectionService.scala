package mesosphere.marathon.core.election

import akka.actor.ActorRef

/**
  * ElectionService is implemented by leadership election mechanisms.
  */
trait ElectionService {
  /**
    * isLeader checks whether this instance is the leader
    *
    * @return true if this instance is the leader
    */
  def isLeader: Boolean

  /**
    * leaderHostPort return a host:port pair of the leader, if it is elected.
    *
    * @return Some(host:port) of the leader, or None if no leader exists or is known
    */
  def leaderHostPort: Option[String]

  /**
    * offerLeadership is called to candidate for leadership. offerLeadership is idem-potent.
    *
    * @param candidate is called back once elected or defeated
    */
  def offerLeadership(candidate: ElectionCandidate): Unit

  /**
    * abdicateLeadership is called to resign from leadership. If this instance is no leader, this
    * call does nothing for reoffer=false. It will call offerLeadership for reoffer=true..
    *
    * @param error is true if the abdication is due to some error.
    * @param reoffer is true if leadership should be offered again after abdication
    */
  def abdicateLeadership(error: Boolean = false, reoffer: Boolean = false): Unit

  /**
    * Subscribe to leadership change events.
    *
    * The given actorRef will initally get the current state via the appropriate
    * [[LocalLeadershipEvent]] message and will be informed of changes after that.
    */
  def subscribe(self: ActorRef)
  /** Unsubscribe to any leadership change events to this actor ref. */
  def unsubscribe(self: ActorRef)
}

/**
  * ElectionCandidate is implemented by a leadership election candidate. There is only one
  * ElectionCandidate per ElectionService.
  */
trait ElectionCandidate {
  /**
    * stopLeadership is called when the candidate was leader, but was defeated. It is guaranteed
    * that calls to stopLeadership and startLeadership alternate and are synchronized.
    */
  def stopLeadership(): Unit

  /**
    * startLeadership is called when the candidate has become leader. It is guaranteed
    * that calls to stopLeadership and startLeadership alternate and are synchronized.
    */
  def startLeadership(): Unit
}

/** Local leadership events. They are not delivered via the event endpoints. */
sealed trait LocalLeadershipEvent

object LocalLeadershipEvent {
  case object ElectedAsLeader extends LocalLeadershipEvent
  case object Standby extends LocalLeadershipEvent
}
