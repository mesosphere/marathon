package mesosphere.marathon.event.http


import akka.actor.ActorRef
import akka.pattern.ask
import mesosphere.marathon.event._
import javax.inject.{Named, Inject}
import mesosphere.marathon.event.http.SubscribersKeeperActor.GetSubscribers
import com.google.common.eventbus.EventBus

class HttpCallbackSubscriptionService @Inject()(
  @Named(HttpEventModule.SubscribersKeeperActor) val subscribersKeeper: ActorRef,
  @Named(EventModule.busName) eventBus: Option[EventBus]) {

  implicit val ec = HttpEventModule.executionContext
  implicit val timeout = HttpEventModule.timeout

  def handleSubscriptionEvent(event: MarathonSubscriptionEvent) = {
    (subscribersKeeper ? event).map{ msg =>
      // Subscribe and Unsubscribe event should be broadcast.
      eventBus.map( _.post(event))
      event
    }
  }

  def getSubscribers = {
    (subscribersKeeper ? GetSubscribers).mapTo[List[String]]
  }

}
