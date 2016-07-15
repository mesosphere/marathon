package mesosphere.marathon.core.event.impl.http

import akka.actor.ActorRef
import akka.event.EventStream
import akka.pattern.ask
import mesosphere.marathon.core.event.impl.http.SubscribersKeeperActor.GetSubscribers
import mesosphere.marathon.core.event.{ EventConf, _ }

import scala.concurrent.Future

class HttpCallbackSubscriptionService(
    val subscribersKeeper: ActorRef,
    eventBus: EventStream,
    conf: EventConf) {

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = conf.eventRequestTimeout

  def handleSubscriptionEvent(event: MarathonSubscriptionEvent): Future[MarathonEvent] =
    (subscribersKeeper ? event).map { msg =>
      // Subscribe and Unsubscribe event should be broadcast.
      eventBus.publish(event)
      event
    }

  def getSubscribers: Future[EventSubscribers] =
    (subscribersKeeper ? GetSubscribers).mapTo[EventSubscribers]
}
