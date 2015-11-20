package mesosphere.marathon.event

import javax.inject.Named

import akka.actor.ActorSystem
import akka.event.EventStream
import com.google.inject.{ AbstractModule, Inject, Provides, Singleton }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.upgrade.{ DeploymentPlan, DeploymentStep }
import org.rogach.scallop.ScallopConf
import org.slf4j.LoggerFactory

//scalastyle:off number.of.types

trait EventSubscriber[C <: ScallopConf, M <: AbstractModule] {
  def configuration(): Class[C]
  def module(): Option[Class[M]]
}

//TODO(FL): Wire this up such that events are optional.
trait EventConfiguration extends ScallopConf {

  lazy val eventSubscriber = opt[String]("event_subscriber",
    descr = "The event subscription module to use. E.g. http_callback.",
    required = false,
    noshort = true)
}

class EventModule(conf: EventConfiguration) extends AbstractModule {

  val log = LoggerFactory.getLogger(getClass.getName)
  def configure() {}

  @Named(EventModule.busName)
  @Provides
  @Singleton
  @Inject
  def provideEventBus(system: ActorSystem): EventStream = system.eventStream
}

object EventModule {
  final val busName = "events"
}

/** Local leadership events. They are not delivered via the event endpoints. */
sealed trait LocalLeadershipEvent

object LocalLeadershipEvent {
  case object ElectedAsLeader extends LocalLeadershipEvent
  case object Standby extends LocalLeadershipEvent
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

// scheduler messages
sealed trait MarathonSchedulerEvent extends MarathonEvent

final case class SchedulerRegisteredEvent(
  frameworkId: String,
  master: String,
  eventType: String = "scheduler_registered_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonSchedulerEvent

final case class SchedulerReregisteredEvent(
  master: String,
  eventType: String = "scheduler_reregistered_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonSchedulerEvent

final case class SchedulerDisconnectedEvent(
  eventType: String = "scheduler_disconnected_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonSchedulerEvent

// event subscriptions

sealed trait MarathonSubscriptionEvent extends MarathonEvent

case class Subscribe(
  clientIp: String,
  callbackUrl: String,
  eventType: String = "subscribe_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonSubscriptionEvent

case class Unsubscribe(
  clientIp: String,
  callbackUrl: String,
  eventType: String = "unsubscribe_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonSubscriptionEvent

case class EventStreamAttached(
  remoteAddress: String,
  eventType: String = "event_stream_attached",
  timestamp: String = Timestamp.now().toString) extends MarathonSubscriptionEvent

case class EventStreamDetached(
  remoteAddress: String,
  eventType: String = "event_stream_detached",
  timestamp: String = Timestamp.now().toString) extends MarathonSubscriptionEvent

// health checks

sealed trait MarathonHealthCheckEvent extends MarathonEvent {
  def appId(): PathId
}

case class AddHealthCheck(
  appId: PathId,
  version: Timestamp,
  healthCheck: HealthCheck,
  eventType: String = "add_health_check_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonHealthCheckEvent

case class RemoveHealthCheck(
  appId: PathId,
  eventType: String = "remove_health_check_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonHealthCheckEvent

case class FailedHealthCheck(
  appId: PathId,
  taskId: String,
  healthCheck: HealthCheck,
  eventType: String = "failed_health_check_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonHealthCheckEvent

case class HealthStatusChanged(
  appId: PathId,
  taskId: String,
  version: String,
  alive: Boolean,
  eventType: String = "health_status_changed_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonHealthCheckEvent

// upgrade messages

sealed trait UpgradeEvent extends MarathonEvent

case class GroupChangeSuccess(
  groupId: PathId,
  version: String,
  eventType: String = "group_change_success",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

case class GroupChangeFailed(
  groupId: PathId,
  version: String,
  reason: String,
  eventType: String = "group_change_failed",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

case class DeploymentSuccess(
  id: String,
  plan: DeploymentPlan,
  eventType: String = "deployment_success",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

case class DeploymentFailed(
  id: String,
  plan: DeploymentPlan,
  eventType: String = "deployment_failed",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

case class DeploymentStatus(
  plan: DeploymentPlan,
  currentStep: DeploymentStep,
  eventType: String = "deployment_info",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

case class DeploymentStepSuccess(
  plan: DeploymentPlan,
  currentStep: DeploymentStep,
  eventType: String = "deployment_step_success",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

case class DeploymentStepFailure(
  plan: DeploymentPlan,
  currentStep: DeploymentStep,
  eventType: String = "deployment_step_failure",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

// Mesos scheduler

case class AppTerminatedEvent(
  appId: PathId,
  eventType: String = "app_terminated_event",
  timestamp: String = Timestamp.now().toString) extends MarathonEvent

case class MesosStatusUpdateEvent(
  slaveId: String,
  taskId: String,
  taskStatus: String,
  message: String,
  appId: PathId,
  host: String,
  ipAddresses: Seq[org.apache.mesos.Protos.NetworkInfo.IPAddress],
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
