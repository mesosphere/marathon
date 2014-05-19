package mesosphere.marathon.event.http

import mesosphere.marathon.event._
import mesosphere.marathon.event.http.SubscribersKeeperActor.GetSubscribers
import akka.actor.ActorRef
import akka.pattern.ask
import akka.event.EventStream
import javax.inject.{ Named, Inject }

class HttpCallbackSubscriptionService @Inject() (
    @Named(HttpEventModule.SubscribersKeeperActor) val subscribersKeeper: ActorRef,
  @Named(EventModule.busName) eventBus: EventStream) {

  implicit val ec = HttpEventModule.executionContext
  implicit val timeout = HttpEventModule.timeout

  def handleSubscriptionEvent(event: MarathonSubscriptionEvent) =
    (subscribersKeeper ? event).map { msg =>
      // Subscribe and Unsubscribe event should be broadcast.
      eventBus.publish(event)
      event
    }

  def getSubscribers =
    (subscribersKeeper ? GetSubscribers).mapTo[EventSubscribers]
}
