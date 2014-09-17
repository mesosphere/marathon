package mesosphere.marathon.event

import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.upgrade.{ DeploymentPlan, DeploymentStep }
import org.rogach.scallop.ScallopConf
import com.google.inject.{ Inject, Singleton, Provides, AbstractModule }
import akka.event.EventStream
import javax.inject.Named
import org.apache.log4j.Logger
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
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
  eventType: String = "api_post_request_received_event",
  timestamp: String = Timestamp.now().toString) extends MarathonEvent

// event subscriptions

sealed trait MarathonSubscriptionEvent extends MarathonEvent {
  def clientIp: String
  def callbackUrl: String
}

case class Subscribe(
  clientIp: String,
  callbackUrl: String,
  eventType: String = "marathon_event_bus_subscribed_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonSubscriptionEvent

case class Unsubscribe(
  clientIp: String,
  callbackUrl: String,
  eventType: String = "marathon_event_bus_unsubscribed_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonSubscriptionEvent

// health checks

sealed trait MarathonHealthCheckEvent extends MarathonEvent {
  def appId(): PathId
}

case class AddHealthCheck(
  appId: PathId,
  healthCheck: HealthCheck,
  eventType: String = "marathon_health_check_added_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonHealthCheckEvent

case class RemoveHealthCheck(
  appId: PathId,
  eventType: String = "marathon_health_check_removed_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonHealthCheckEvent

case class FailedHealthCheck(
  appId: PathId,
  taskId: String,
  healthCheck: HealthCheck,
  eventType: String = "marathon_health_check_failed_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonHealthCheckEvent

case class HealthStatusChanged(
  appId: PathId,
  taskId: String,
  version: String,
  alive: Boolean,
  eventType: String = "marathon_health_check_status_updated_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonHealthCheckEvent

// upgrade messages

trait UpgradeEvent extends MarathonEvent

case class GroupChangeSuccess(
  groupId: PathId,
  version: String,
  eventType: String = "marathon_group_change_succeeded_event",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

case class GroupChangeFailed(
  groupId: PathId,
  version: String,
  reason: String,
  eventType: String = "marathon_group_change_failed_event",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

case class DeploymentSuccess(
  id: String,
  eventType: String = "marathon_deployment_succeeded_event",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

case class DeploymentFailed(
  id: String,
  eventType: String = "marathon_deployment_failed_event",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

case class DeploymentStatus(
  plan: DeploymentPlan,
  currentStep: DeploymentStep,
  eventType: String = "marathon_deployment_status_updated_event",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

case class DeploymentStepSuccess(
  plan: DeploymentPlan,
  currentStep: DeploymentStep,
  eventType: String = "marathon_deployment_step_succeeded_event",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

case class DeploymentStepFailure(
  plan: DeploymentPlan,
  currentStep: DeploymentStep,
  eventType: String = "marathon_deployment_step_failed_event",
  timestamp: String = Timestamp.now().toString) extends UpgradeEvent

// Mesos scheduler

case class MesosStatusUpdateEvent(
  slaveId: String,
  taskId: String,
  taskStatus: String,
  appId: PathId,
  host: String,
  ports: Iterable[Integer],
  version: String,
  eventType: String = "mesos_status_update_received_event",
  timestamp: String = Timestamp.now().toString) extends MarathonEvent

case class MesosFrameworkMessageEvent(
  executorId: String,
  slaveId: String,
  message: Array[Byte],
  eventType: String = "mesos_framework_message_received_event",
  timestamp: String = Timestamp.now().toString) extends MarathonEvent
