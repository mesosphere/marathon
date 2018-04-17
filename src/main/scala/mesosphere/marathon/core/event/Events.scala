package mesosphere.marathon
package core.event

import akka.event.EventStream
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.health.HealthCheck
import mesosphere.marathon.core.instance.update.InstanceChange
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.core.deployment.{ DeploymentPlan, DeploymentStep }
import org.apache.mesos.{ Protos => Mesos }

import scala.collection.immutable.Seq

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

case class PodEvent(
    clientIp: String,
    uri: String,
    podEventType: PodEvent.Kind,
    timestamp: String = Timestamp.now().toString) extends MarathonEvent {
  override val eventType = podEventType.label
}

object PodEvent {
  sealed trait Kind {
    val label: String
  }
  case object Created extends Kind {
    val label = "pod_created_event"
  }
  case object Updated extends Kind {
    val label = "pod_updated_event"
  }
  case object Deleted extends Kind {
    val label = "pod_deleted_event"
  }
}

// scheduler messages
sealed trait MarathonSchedulerEvent extends MarathonEvent

case class SchedulerRegisteredEvent(
  frameworkId: String,
  master: String,
  eventType: String = "scheduler_registered_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonSchedulerEvent

case class SchedulerReregisteredEvent(
  master: String,
  eventType: String = "scheduler_reregistered_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonSchedulerEvent

case class SchedulerDisconnectedEvent(
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
  instanceId: Instance.Id,
  healthCheck: HealthCheck,
  eventType: String = "failed_health_check_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonHealthCheckEvent

case class HealthStatusChanged(
  appId: PathId,
  instanceId: Instance.Id,
  version: Timestamp,
  alive: Boolean,
  eventType: String = "health_status_changed_event",
  timestamp: String = Timestamp.now().toString)
    extends MarathonHealthCheckEvent

/**
  * Event published when an instance is killed because it failed too many MarathonHealthChecks
  * This will only be published for single container instances as we don't support Marathon
  * health checks for pods.
  */
case class UnhealthyInstanceKillEvent(
  appId: PathId,
  taskId: Task.Id,
  instanceId: Instance.Id,
  version: Timestamp,
  reason: String,
  host: String,
  slaveId: Option[String],
  eventType: String = "unhealthy_instance_kill_event",
  timestamp: String = Timestamp.now().toString) extends MarathonHealthCheckEvent

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

// TODO(jdef) rename this RunSpecTerminatedEvent since that's how it's actually used
case class AppTerminatedEvent(
  appId: PathId,
  eventType: String = "app_terminated_event",
  timestamp: String = Timestamp.now().toString) extends MarathonEvent

case class MesosStatusUpdateEvent(
  slaveId: String,
  taskId: Task.Id,
  taskStatus: Mesos.TaskState,
  message: String,
  appId: PathId,
  host: String,
  ipAddresses: Seq[org.apache.mesos.Protos.NetworkInfo.IPAddress],
  ports: Seq[Int],
  version: String,
  eventType: String = "status_update_event",
  timestamp: String = Timestamp.now().toString) extends MarathonEvent

/** Event indicating a status change for a known instance */
case class InstanceChanged(
    id: Instance.Id,
    runSpecVersion: Timestamp,
    runSpecId: PathId,
    condition: Condition,
    instance: Instance) extends MarathonEvent {
  override val eventType: String = "instance_changed_event"
  override val timestamp: String = Timestamp.now().toString
}
object InstanceChanged {
  def apply(instanceChange: InstanceChange): InstanceChanged = {
    InstanceChanged(instanceChange.id, instanceChange.runSpecVersion,
      instanceChange.runSpecId, instanceChange.condition, instanceChange.instance)
  }
}

/** Event indicating an unknown instance is terminal */
case class UnknownInstanceTerminated(
    id: Instance.Id,
    runSpecId: PathId,
    condition: Condition) extends MarathonEvent {
  override val eventType: String = "unknown_instance_terminated_event"
  override val timestamp: String = Timestamp.now().toString
}

case class InstanceHealthChanged(
    id: Instance.Id,
    runSpecVersion: Timestamp,
    runSpecId: PathId,
    healthy: Option[Boolean]) extends MarathonEvent {
  override val eventType: String = "instance_health_changed_event"
  override val timestamp: String = Timestamp.now().toString
}

case class MesosFrameworkMessageEvent(
  executorId: String,
  slaveId: String,
  message: Array[Byte],
  eventType: String = "framework_message_event",
  timestamp: String = Timestamp.now().toString) extends MarathonEvent

object Events {
  def maybePost(event: MarathonEvent)(implicit eventBus: EventStream): Unit =
    eventBus.publish(event)
}
