package mesosphere.marathon.core.election

import scala.concurrent.Future

/**
  * ElectionService is implemented by leadership election mechanisms.
  */
trait ElectionService {
  /**
    * isLeader checks whether this instance is the leader
    * @return true if this instance is the leader
    */
  def isLeader: Boolean

  /**
    * leaderHostPort return a host:port pair of the leader, if it is elected.
    * @return Some(host:port) of the leader, or None if no leader exists or is known
    */
  def leaderHostPort: Option[String]

  /**
    * offerLeadership is called to candidate for leadership. offerLeadership is idem-potent.
    * @param candidate is called back once elected or defeated
    */
  def offerLeadership(candidate: ElectionCandidate): Unit

  /**
    * abdicateLeadership is called to resign from leadership. If this instance is no leader, this
    * call does nothing for reoffer=false. It will call offerLeadership for reoffer=true..
    * @param error is true if the abdication is due to some error.
    * @param reoffer is true if leadership should be offered again after abdiction
    */
  def abdicateLeadership(error: Boolean = false, reoffer: Boolean = false): Unit
}

/**
  * ElectionCandidate is implemented by a leadership election candidate. There is only one
  * ElectionCandidate per ElectionService.
  */
trait ElectionCandidate {
  /**
    * stopLeadership is called when the candidate was leader, but was defeated. It is guaranteed
    * that before startLeadership has been called.
    */
  def stopLeadership(): Unit

  /**
    * startLeadership is called when the candidate has become leader. It is guaranteed that
    * before stopLeadership has been called if the instance was leader.
    */
  def startLeadership(): Unit
}

/**
  * ElectionCallback is implemented by callback receivers which have to listen for leadership
  * changes of the current instance.
  */
trait ElectionCallback {
  /**
    * Will get called _before_ the ElectionCandidate (usually the scheduler driver) starts leadership.
    */
  def onElected: Future[Unit]

  /**
    * Will get called after leadership is abdicated.
    */
  def onDefeated: Future[Unit]
}
