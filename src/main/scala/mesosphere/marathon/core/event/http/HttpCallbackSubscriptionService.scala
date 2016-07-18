package mesosphere.marathon.core.event.http

import mesosphere.marathon.core.event.{ MarathonEvent, MarathonSubscriptionEvent }

import scala.concurrent.Future

trait HttpCallbackSubscriptionService {
  def handleSubscriptionEvent(event: MarathonSubscriptionEvent): Future[MarathonEvent]
  def getSubscribers: Future[EventSubscribers]
}
