package mesosphere.marathon.event.http


import akka.actor.ActorRef
import akka.pattern.ask
import mesosphere.marathon.event._
import javax.inject.{Named, Inject}
import mesosphere.marathon.event.http.HttpEventActor.GetSubscribers

class HttpCallbackSubscriptionService @Inject()(
  @Named(HttpEventModule.StatusUpdateActor) val actor: ActorRef) {

  implicit val timeout = HttpEventModule.timeout

  def handleSubscriptionEvent(event: MarathonSubscriptionEvent) = {
    actor ? event
  }

  def getSubscribers = {
    (actor ? GetSubscribers).mapTo[List[String]]
  }

}
