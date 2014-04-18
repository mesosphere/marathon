package mesosphere.marathon.event.exec

import akka.actor.ActorRef
import com.google.common.eventbus.{EventBus, Subscribe}
import mesosphere.marathon.event.{EventModule, EventSubscriber, MarathonEvent}
import java.util.logging.Logger
import javax.inject.{Named, Inject}

class ExecCallbackEventSubscriber @Inject()(
    @Named(ExecEventModule.StatusUpdateActor) val actor: ActorRef,
    @Named(EventModule.busName) val eventBus: Option[EventBus])
  extends EventSubscriber[ExecEventConfiguration, ExecEventModule] {

  require(eventBus.nonEmpty, "Tried to bind Exec event publishing without " +
    "event bus.")
  eventBus.get.register(this)

  val log = Logger.getLogger(getClass.getName)

  @Subscribe
  def handleMarathonEvent(event: MarathonEvent) {
    log.info("Received message from bus:" + event)
    actor ! event
  }

  def configuration() = {
    classOf[ExecEventConfiguration]
  }

  def module() = {
    Some(classOf[ExecEventModule])
  }
}
