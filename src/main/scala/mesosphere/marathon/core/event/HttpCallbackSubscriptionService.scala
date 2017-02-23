package mesosphere.marathon
package core.event

import scala.concurrent.Future

trait HttpCallbackSubscriptionService {
  def handleSubscriptionEvent(event: MarathonSubscriptionEvent): Future[MarathonEvent]
  def getSubscribers: Future[EventSubscribers]
}
