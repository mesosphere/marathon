package mesosphere.marathon.core.matcher.manager

import mesosphere.marathon.core.matcher.base.OfferMatcher

import scala.concurrent.{ ExecutionContext, Future }

trait OfferMatcherManager {
  def addSubscription(offerMatcher: OfferMatcher)(implicit ec: ExecutionContext): Future[Unit]
  def removeSubscription(offerMatcher: OfferMatcher)(implicit ec: ExecutionContext): Future[Unit]

  /** Increases the number of allowed future task matches by the given number. */
  def addLaunchTokens(tokens: Int): Unit
  /** Sets the number of allowed future task matches to the given number. */
  def setLaunchTokens(tokens: Int): Unit
}
