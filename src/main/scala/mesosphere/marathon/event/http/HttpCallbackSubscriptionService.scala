package mesosphere.marathon.event.http

import mesosphere.marathon.event._
import mesosphere.marathon.event.http.SubscribersKeeperActor.GetSubscribers
import akka.actor.ActorRef
import akka.pattern.ask
import com.google.common.eventbus.EventBus
import javax.inject.{ Named, Inject }

class HttpCallbackSubscriptionService @Inject() (
    @Named(HttpEventModule.SubscribersKeeperActor) val subscribersKeeper: ActorRef,
  @Named(EventModule.busName) eventBus: EventBus) {

  implicit val ec = HttpEventModule.executionContext
  implicit val timeout = HttpEventModule.timeout

  def handleSubscriptionEvent(event: MarathonSubscriptionEvent) =
    (subscribersKeeper ? event).map { msg =>
      // Subscribe and Unsubscribe event should be broadcast.
      eventBus.post(event)
      event
    }

  def getSubscribers =
    (subscribersKeeper ? GetSubscribers).mapTo[EventSubscribers]
}
