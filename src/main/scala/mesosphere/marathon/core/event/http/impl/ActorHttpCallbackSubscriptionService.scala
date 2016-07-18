package mesosphere.marathon.core.event.http.impl

import akka.actor.ActorRef
import akka.event.EventStream
import akka.pattern.ask
import mesosphere.marathon.core.event.http.{ EventSubscribers, HttpCallbackSubscriptionService }
import mesosphere.marathon.core.event.http.impl.SubscribersKeeperActor.GetSubscribers
import mesosphere.marathon.core.event.{ EventConf, _ }

import scala.concurrent.Future

class ActorHttpCallbackSubscriptionService(
  val subscribersKeeper: ActorRef,
  eventBus: EventStream,
  conf: EventConf)
    extends HttpCallbackSubscriptionService {

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = conf.eventRequestTimeout

  override def handleSubscriptionEvent(event: MarathonSubscriptionEvent): Future[MarathonEvent] =
    (subscribersKeeper ? event).map { msg =>
      // Subscribe and Unsubscribe event should be broadcast.
      eventBus.publish(event)
      event
    }

  override def getSubscribers: Future[EventSubscribers] =
    (subscribersKeeper ? GetSubscribers).mapTo[EventSubscribers]
}
