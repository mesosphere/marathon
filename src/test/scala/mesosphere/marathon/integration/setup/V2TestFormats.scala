package mesosphere.marathon
package integration.setup

import mesosphere.marathon.api.v2.json.AppUpdate
import mesosphere.marathon.core.event._
import mesosphere.marathon.state.{ Group, RootGroup, Timestamp }
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.raml.Raml
import play.api.libs.json._

/**
  * Formats for JSON objects which do not need write support in the production code.
  */
object V2TestFormats {
  import mesosphere.marathon.api.v2.json.Formats._

  implicit lazy val DeploymentPlanReads: Reads[DeploymentPlan] = Reads { js =>
    JsSuccess(
      DeploymentPlan(
        original = RootGroup.fromGroup((js \ "original").as[Group]),
        target = RootGroup.fromGroup((js \ "target").as[Group]),
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

  implicit lazy val v2AppUpdateWrite: Writes[AppUpdate] = Writes { update =>
    Json.obj(
      "id" -> update.id.map(_.toString),
      "cmd" -> update.cmd,
      "args" -> update.args,
      "user" -> update.user,
      "env" -> update.env,
      "instances" -> update.instances,
      "cpus" -> update.cpus,
      "mem" -> update.mem,
      "disk" -> update.disk,
      "executor" -> update.executor,
      "constraints" -> update.constraints,
      "fetch" -> update.fetch,
      "storeUrls" -> update.storeUrls,
      "portDefinitions" -> update.portDefinitions,
      "requirePorts" -> update.requirePorts,
      "backoffSeconds" -> update.backoff.map(_.toSeconds),
      "backoffFactor" -> update.backoffFactor,
      "maxLaunchDelaySeconds" -> update.maxLaunchDelay.map(_.toSeconds),
      "container" -> update.container,
      "healthChecks" -> update.healthChecks.map(_.map(Json.toJson(_)(HealthCheckFormat))),
      "readinessChecks" -> update.readinessChecks,
      "taskKillGracePeriodSeconds" -> update.taskKillGracePeriod.map(_.toSeconds),
      "dependencies" -> update.dependencies,
      "upgradeStrategy" -> update.upgradeStrategy,
      "labels" -> update.labels,
      "version" -> update.version,
      "acceptedResourceRoles" -> update.acceptedResourceRoles,
      "ipAddress" -> update.ipAddress,
      "unreachableStrategy" -> Raml.toRaml(update.unreachableStrategy)
    )
  }
}
