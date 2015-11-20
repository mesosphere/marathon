package mesosphere.marathon.integration.setup

import mesosphere.marathon.api.v2.json.{ V2AppUpdate, V2Group }
import mesosphere.marathon.event._
import mesosphere.marathon.event.http.EventSubscribers
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.upgrade.DeploymentPlan
import org.apache.mesos.Protos
import play.api.libs.json._

/**
  * Formats for JSON objects which do not need write support in the production code.
  */
object V2TestFormats {
  import mesosphere.marathon.api.v2.json.Formats._

  implicit lazy val DeploymentPlanReads: Reads[DeploymentPlan] = Reads { js =>
    JsSuccess(
      DeploymentPlan(
        original = (js \ "original").as[V2Group].toGroup(),
        target = (js \ "target").as[V2Group].toGroup(),
        version = (js \ "version").as[Timestamp]).copy(id = (js \ "id").as[String]
        )
    )
  }

  implicit lazy val SubscribeReads: Reads[Subscribe] = Json.reads[Subscribe]
  implicit lazy val UnsubscribeReads: Reads[Unsubscribe] = Json.reads[Unsubscribe]
  implicit lazy val EventStreamAttachedReads: Reads[EventStreamAttached] = Json.reads[EventStreamAttached]
  implicit lazy val EventStreamDetachedReads: Reads[EventStreamDetached] = Json.reads[EventStreamDetached]
  implicit lazy val AddHealthCheckReads: Reads[AddHealthCheck] = Json.reads[AddHealthCheck]
  implicit lazy val RemoveHealthCheckReads: Reads[RemoveHealthCheck] = Json.reads[RemoveHealthCheck]
  implicit lazy val FailedHealthCheckReads: Reads[FailedHealthCheck] = Json.reads[FailedHealthCheck]
  implicit lazy val HealthStatusChangedReads: Reads[HealthStatusChanged] = Json.reads[HealthStatusChanged]
  implicit lazy val GroupChangeSuccessReads: Reads[GroupChangeSuccess] = Json.reads[GroupChangeSuccess]
  implicit lazy val GroupChangeFailedReads: Reads[GroupChangeFailed] = Json.reads[GroupChangeFailed]
  implicit lazy val DeploymentSuccessReads: Reads[DeploymentSuccess] = Json.reads[DeploymentSuccess]
  implicit lazy val DeploymentFailedReads: Reads[DeploymentFailed] = Json.reads[DeploymentFailed]
  //  implicit lazy val DeploymentStatusReads: Reads[DeploymentStatus] = Json.reads[DeploymentStatus]
  //  implicit lazy val DeploymentStepSuccessReads: Reads[DeploymentStepSuccess] = Json.reads[DeploymentStepSuccess]
  //  implicit lazy val DeploymentStepFailureReads: Reads[DeploymentStepFailure] = Json.reads[DeploymentStepFailure]
  implicit lazy val MesosStatusUpdateEventReads: Reads[MesosStatusUpdateEvent] = Json.reads[MesosStatusUpdateEvent]
  implicit lazy val MesosFrameworkMessageEventReads: Reads[MesosFrameworkMessageEvent] =
    Json.reads[MesosFrameworkMessageEvent]
  implicit lazy val SchedulerDisconnectedEventReads: Reads[SchedulerDisconnectedEvent] =
    Json.reads[SchedulerDisconnectedEvent]
  implicit lazy val SchedulerRegisteredEventWritesReads: Reads[SchedulerRegisteredEvent] =
    Json.reads[SchedulerRegisteredEvent]
  implicit lazy val SchedulerReregisteredEventWritesReads: Reads[SchedulerReregisteredEvent] =
    Json.reads[SchedulerReregisteredEvent]

  implicit lazy val eventSubscribersReads: Reads[EventSubscribers] = Reads { subscribersJson =>
    JsSuccess(EventSubscribers(urls = (subscribersJson \ "callbackUrls").asOpt[Set[String]].getOrElse(Set.empty)))
  }

  implicit lazy val v2AppUpdateWrite: Writes[V2AppUpdate] = Writes { update =>
    Json.obj(
      "id" -> update.id.map(_.toString),
      "cmd" -> update.cmd,
      "args" -> update.args,
      "user" -> update.user,
      "env" -> update.env,
      "instances" -> update.instances.map(_.toInt),
      "cpus" -> update.cpus.map(_.toDouble),
      "mem" -> update.mem.map(_.toDouble),
      "disk" -> update.disk.map(_.toDouble),
      "executor" -> update.executor,
      "constraints" -> update.constraints,
      "uris" -> update.uris,
      "storeUrls" -> update.storeUrls,
      "ports" -> update.ports,
      "requirePorts" -> update.requirePorts,
      "backoffSeconds" -> update.backoff.map(_.toSeconds),
      "backoffFactor" -> update.backoffFactor,
      "maxLaunchDelaySeconds" -> update.maxLaunchDelay.map(_.toSeconds),
      "container" -> update.container,
      "healthChecks" -> update.healthChecks,
      "dependencies" -> update.dependencies,
      "upgradeStrategy" -> update.upgradeStrategy,
      "labels" -> update.labels,
      "version" -> update.version,
      "acceptedResourceRoles" -> update.acceptedResourceRoles
    )
  }
}
