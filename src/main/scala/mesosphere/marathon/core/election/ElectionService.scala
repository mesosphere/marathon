package mesosphere.marathon
package core.election

import akka.actor.ActorRef

/**
  * ElectionService is implemented by leadership election mechanisms.
  *
  * This trait is used in conjunction with [[ElectionCandidate]]. From their point of view,
  * a leader election works as follow:
  *
  * -> ElectionService.offerLeadership(candidate)     |      - A leader election is triggered.
  *                                                          — Once `candidate` is elected as a leader,
  *                                                            its `startLeadership` is called.
  *
  * Please note that upon a call to [[ElectionService.abdicateLeadership]], or
  * any error in any of method of [[ElectionService]], or a leadership loss,
  * [[ElectionCandidate.stopLeadership]] is called if [[ElectionCandidate.startLeadership]]
  * has been called before, and JVM gets shutdown.
  *
  * It effectively means that a particular instance of Marathon can be elected at most once during its lifetime.
  */
trait ElectionService {
  /**
    * isLeader checks whether this instance is the leader
    *
    * @return true if this instance is the leader
    */
  def isLeader: Boolean

  /**
    * localHostPort return a host:port pair of this running instance that is used for discovery.
    *
    * @return host:port of this instance.
    */
  def localHostPort: String

  /**
    * leaderHostPort return a host:port pair of the leader, if it is elected.
    *
    * @return Some(host:port) of the leader, or None if no leader exists or is known
    */
  def leaderHostPort: Option[String]

  /**
    * offerLeadership is called to candidate for leadership. It must be called by candidate only once.
    *
    * @param candidate is called back once elected or defeated
    */
  def offerLeadership(candidate: ElectionCandidate): Unit

  /**
    * abdicateLeadership is called to resign from leadership. By the time this method returns,
    * it can be safely assumed the leadership has been abdicated. This method can be called even
    * if [[offerLeadership]] wasn't called prior to that, and it will result in Marathon stop and JVM shutdown.
    */
  def abdicateLeadership(): Unit

  /**
    * Subscribe to leadership change events.
    *
    * The given actorRef will initially get the current state via the appropriate
    * [[LocalLeadershipEvent]] message and will be informed of changes after that.
    *
    * Upon becoming a leader, [[LocalLeadershipEvent.ElectedAsLeader]] is published. Upon leadership loss,
    * [[LocalLeadershipEvent.Standby]] is sent.
    */
  def subscribe(self: ActorRef): Unit

  /**
    * Unsubscribe to any leadership change events for the given [[ActorRef]].
    */
  def unsubscribe(self: ActorRef): Unit
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
