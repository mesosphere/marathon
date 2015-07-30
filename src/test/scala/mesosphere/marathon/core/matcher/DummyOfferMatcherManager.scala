package mesosphere.marathon.core.matcher

import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager

import scala.concurrent.{ Future, ExecutionContext }

class DummyOfferMatcherManager extends OfferMatcherManager {
  @volatile
  var offerMatchers = Vector.empty[OfferMatcher]

  override def addSubscription(offerMatcher: OfferMatcher)(
    implicit ec: ExecutionContext): Future[Unit] =
    {
      Future.successful(offerMatchers :+= offerMatcher)
    }

  override def removeSubscription(offerMatcher: OfferMatcher)(implicit ec: ExecutionContext): Future[Unit] = {
    Future.successful(offerMatchers = offerMatchers.filter(_ != offerMatcher))
  }

  def addLaunchTokens(tokens: Int): Unit = ???
  def setLaunchTokens(tokens: Int): Unit = ???
}
