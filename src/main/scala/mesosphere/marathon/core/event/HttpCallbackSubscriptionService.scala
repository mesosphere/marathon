package mesosphere.marathon.core.event

import scala.concurrent.Future

trait HttpCallbackSubscriptionService {
  def handleSubscriptionEvent(event: MarathonSubscriptionEvent): Future[MarathonEvent]
  def getSubscribers: Future[EventSubscribers]
}
