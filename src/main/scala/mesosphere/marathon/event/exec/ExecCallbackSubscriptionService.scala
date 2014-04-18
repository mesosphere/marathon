package mesosphere.marathon.event.exec


import mesosphere.marathon.event._
import mesosphere.marathon.event.exec.SubscribersKeeperActor.GetSubscribers
import akka.actor.ActorRef
import akka.pattern.ask
import com.google.common.eventbus.EventBus
import javax.inject.{Named, Inject}

class ExecCallbackSubscriptionService @Inject()(
  @Named(ExecEventModule.SubscribersKeeperActor) val subscribersKeeper: ActorRef,
  @Named(EventModule.busName) eventBus: Option[EventBus]) {

  implicit val ec = ExecEventModule.executionContext
  implicit val timeout = ExecEventModule.timeout

  def handleSubscriptionEvent(event: MarathonSubscriptionEvent) = {
    (subscribersKeeper ? event).map{ msg =>
      // Subscribe and Unsubscribe event should be broadcast.
      eventBus.map( _.post(event))
      event
    }
  }

  def getSubscribers = {
    (subscribersKeeper ? GetSubscribers).mapTo[EventSubscribers]
  }

}
