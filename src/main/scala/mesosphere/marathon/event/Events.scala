package mesosphere.marathon.event

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.health.HealthCheck
import org.rogach.scallop.ScallopConf
import com.google.inject.{Singleton, Provides, AbstractModule}
import com.google.common.eventbus.{AsyncEventBus, EventBus}
import java.util.concurrent.Executors
import javax.inject.Named
import org.apache.log4j.Logger

trait EventSubscriber[C <: ScallopConf, M <: AbstractModule] {
  def configuration(): Class[C]
  def module(): Option[Class[M]]
}

//TODO(FL): Wire this up such that events are optional.
trait EventConfiguration extends ScallopConf {

  lazy val eventSubscriber = opt[String]("event_subscriber",
    descr = "e.g. http_callback",
    required = false,
    noshort = true)
}

class EventModule(conf: EventConfiguration) extends AbstractModule {

  val log = Logger.getLogger(getClass.getName)
  def configure() {}

  @Named(EventModule.busName)
  @Provides
  @Singleton
  def provideEventBus(): Option[EventBus] = {
    if (conf.eventSubscriber.isSupplied) {
      log.info("Creating Event Bus.")
      val pool =  Executors.newCachedThreadPool()
      val bus = new AsyncEventBus(pool)
      Some(bus)
    } else {
      None
    }
  }
}

object EventModule {
  final val busName = "events"
}

sealed trait MarathonEvent {
  val eventType: String
}

case class ApiPostEvent(
  clientIp: String,
  uri: String,
  appDefinition: AppDefinition,
  eventType: String = "api_post_event"
) extends MarathonEvent

case class MesosStatusUpdateEvent(
  slaveId: String,
  taskId: String,
  taskStatus: String,
  appId: String,
  host: String,
  ports: Iterable[Integer],
  eventType: String = "status_update_event"
) extends MarathonEvent

// event subscriptions

sealed trait MarathonSubscriptionEvent extends MarathonEvent {
  def clientIp: String
  def callbackUrl: String
}

case class Subscribe(
  clientIp: String,
  callbackUrl: String,
  eventType: String = "subscribe_event"
) extends MarathonSubscriptionEvent

case class Unsubscribe(
  clientIp: String,
  callbackUrl: String,
  eventType: String = "unsubscribe_event"
) extends MarathonSubscriptionEvent

// health checks

sealed trait MarathonHealthCheckEvent extends MarathonEvent

case class AddHealthCheck(
  healthCheck: HealthCheck,
  eventType: String = "add_health_check_event"
) extends MarathonHealthCheckEvent

case class RemoveHealthCheck(
  appId: String,
  eventType: String = "remove_health_check_event"
) extends MarathonHealthCheckEvent

case class FailedHealthCheck(
  appId: String,
  taskId: String,
  healthCheck: HealthCheck,
  eventType: String = "failed_health_check_event"
) extends MarathonHealthCheckEvent

case class HealthStatusChanged(
  appId: String,
  taskId: String,
  alive: Boolean,
  eventType: String = "health_status_changed_event"
) extends MarathonHealthCheckEvent

// framework messages

case class FrameworkMessageEvent(
   executorId: String,
   slaveId: String,
   message: Array[Byte],
   eventType: String = "framework_message_event")
extends MarathonEvent
