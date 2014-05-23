package mesosphere.marathon.event

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.health.HealthCheck
import org.rogach.scallop.ScallopConf
import com.google.inject.{Inject, Singleton, Provides, AbstractModule}
import akka.event.EventStream
import javax.inject.Named
import org.apache.log4j.Logger
import mesosphere.marathon.state.Timestamp
import akka.actor.ActorSystem

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
  @Inject
  def provideEventBus(system: ActorSystem): EventStream = system.eventStream
    else {
}

object EventModule {
  final val busName = "events"
}

sealed trait MarathonEvent {
  val eventType: String
  val timestamp: String
}

// api

case class ApiPostEvent(
  clientIp: String,
  uri: String,
  appDefinition: AppDefinition,
  eventType: String = "api_post_event",
  timestamp: String = Timestamp.now().toString) extends MarathonEvent

// event subscriptions

sealed trait MarathonSubscriptionEvent extends MarathonEvent {
  def clientIp: String
  def callbackUrl: String
}

case class Subscribe(
  clientIp: String,
  callbackUrl: String,
  eventType: String = "subscribe_event",
  timestamp: String = Timestamp.now().toString) extends MarathonSubscriptionEvent

case class Unsubscribe(
  clientIp: String,
  callbackUrl: String,
  eventType: String = "unsubscribe_event",
  timestamp: String = Timestamp.now().toString) extends MarathonSubscriptionEvent

// health checks

sealed trait MarathonHealthCheckEvent extends MarathonEvent

case class AddHealthCheck(
  healthCheck: HealthCheck,
  eventType: String = "add_health_check_event",
  timestamp: String = Timestamp.now().toString) extends MarathonHealthCheckEvent

case class RemoveHealthCheck(
  appId: String,
  eventType: String = "remove_health_check_event",
  timestamp: String = Timestamp.now().toString) extends MarathonHealthCheckEvent

case class FailedHealthCheck(
  appId: String,
  taskId: String,
  healthCheck: HealthCheck,
  eventType: String = "failed_health_check_event",
  timestamp: String = Timestamp.now().toString) extends MarathonHealthCheckEvent

case class HealthStatusChanged(
  appId: String,
  taskId: String,
  alive: Boolean,
  eventType: String = "health_status_changed_event",
  timestamp: String = Timestamp.now().toString) extends MarathonHealthCheckEvent

// upgrade messages

trait UpgradeEvent extends MarathonEvent

case class GroupChangeSuccess(
  groupId: String,
  version: String,
  eventType:String = "group_change_success",
  timestamp: String = Timestamp.now().toString
) extends UpgradeEvent

case class GroupChangeFailed(
  groupId: String,
  version: String,
  reason:String,
  eventType: String = "group_change_failed",
  timestamp: String = Timestamp.now().toString
) extends UpgradeEvent

case class RestartSuccess(
  appId: String,
  eventType: String = "restart_success",
  timestamp: String = Timestamp.now().toString
) extends UpgradeEvent

case class RestartFailed(
  appId: String,
  eventType: String = "restart_failed",
  timestamp: String = Timestamp.now().toString
) extends UpgradeEvent

case class RollbackSuccess(
  appId: String,
  eventType: String = "rollback_success",
  timestamp: String = Timestamp.now().toString
) extends UpgradeEvent

case class RollbackFailed(
  appId: String,
  eventType: String = "rollback_failed",
  timestamp: String = Timestamp.now().toString
) extends UpgradeEvent

// Mesos scheduler

case class MesosStatusUpdateEvent(
  slaveId: String,
  taskId: String,
  taskStatus: String,
  appId: String,
  host: String,
  ports: Iterable[Integer],
  version: String,
  eventType: String = "status_update_event",
  timestamp: String = Timestamp.now().toString) extends MarathonEvent

case class MesosFrameworkMessageEvent(
  executorId: String,
  slaveId: String,
  message: Array[Byte],
  eventType: String = "framework_message_event",
  timestamp: String = Timestamp.now().toString) extends MarathonEvent
