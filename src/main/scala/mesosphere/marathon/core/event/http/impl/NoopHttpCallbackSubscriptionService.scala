package mesosphere.marathon.core.event.http.impl

import mesosphere.marathon.BadRequestException
import mesosphere.marathon.core.event.http.{ EventSubscribers, HttpCallbackSubscriptionService }
import mesosphere.marathon.core.event.{ MarathonEvent, MarathonSubscriptionEvent }

import scala.concurrent.Future

object NoopHttpCallbackSubscriptionService extends HttpCallbackSubscriptionService {
  val ERROR_MESSAGE =
    """http event callback system is not running on this Marathon instance. Please re-start this instance with
      |"--event_subscriber http_callback".""".stripMargin

  override def handleSubscriptionEvent(event: MarathonSubscriptionEvent): Future[MarathonEvent] = {
    Future.failed(new BadRequestException(ERROR_MESSAGE))
  }

  override def getSubscribers: Future[EventSubscribers] = Future.failed(new BadRequestException(ERROR_MESSAGE))
}
