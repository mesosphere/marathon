package mesosphere.marathon.event.http

import akka.actor.ActorRef
import akka.event.EventStream
import mesosphere.marathon.event.{ EventModule, EventSubscriber, MarathonEvent }
import javax.inject.{ Named, Inject }

class HttpCallbackEventSubscriber @Inject() (
  @Named(HttpEventModule.StatusUpdateActor) val actor: ActorRef,
  @Named(EventModule.busName) val eventBus: EventStream)
    extends EventSubscriber[HttpEventConfiguration, HttpEventModule] {

  eventBus.subscribe(actor, classOf[MarathonEvent])

  def configuration() = {
    classOf[HttpEventConfiguration]
  }

  def module() = {
    Some(classOf[HttpEventModule])
  }
}
