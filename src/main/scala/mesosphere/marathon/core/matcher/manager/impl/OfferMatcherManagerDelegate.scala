package mesosphere.marathon.core.matcher.manager.impl

import akka.actor.ActorRef
import akka.util.Timeout
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManager
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

import akka.pattern.ask

private[matcher] object OfferMatcherManagerDelegate {
  sealed trait ChangeMatchersRequest
  case class AddOrUpdateMatcher(consumer: OfferMatcher) extends ChangeMatchersRequest
  case class RemoveMatcher(consumer: OfferMatcher) extends ChangeMatchersRequest

  sealed trait ChangeConsumersResponse
  case class MatcherAdded(consumer: OfferMatcher) extends ChangeConsumersResponse
  case class MatcherRemoved(consumer: OfferMatcher) extends ChangeConsumersResponse

  case class SetTaskLaunchTokens(tokens: Int)
  case class AddTaskLaunchTokens(tokens: Int)
}

private[matcher] class OfferMatcherManagerDelegate(actorRef: ActorRef) extends OfferMatcherManager {

  private[this] implicit val timeout: Timeout = 2.seconds

  override def addSubscription(offerMatcher: OfferMatcher)(implicit ec: ExecutionContext): Future[Unit] = {
    val future = actorRef ? OfferMatcherManagerDelegate.AddOrUpdateMatcher(offerMatcher)
    future.map(_ => ())
  }

  override def removeSubscription(offerMatcher: OfferMatcher)(implicit ec: ExecutionContext): Future[Unit] = {
    val future = actorRef ? OfferMatcherManagerDelegate.RemoveMatcher(offerMatcher)
    future.map(_ => ())
  }

  override def addLaunchTokens(tokens: Int): Unit = actorRef ! OfferMatcherManagerDelegate.AddTaskLaunchTokens(tokens)
  override def setLaunchTokens(tokens: Int): Unit = actorRef ! OfferMatcherManagerDelegate.SetTaskLaunchTokens(tokens)
}
