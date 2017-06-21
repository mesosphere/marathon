package mesosphere.marathon
package integration.setup

import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.event._
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.raml.RamlConversionTesting._
import mesosphere.marathon.state.{ Group, RootGroup, Timestamp }
import play.api.libs.json._

/**
  * Formats for JSON objects which do not need write support in the production code.
  */
object V2TestFormats {
  import mesosphere.marathon.api.v2.json.Formats._

  implicit lazy val DeploymentPlanReads: Reads[DeploymentPlan] = Reads { js =>
    implicit lazy val GroupReads: Reads[Group] = Reads { js =>
      JsSuccess(Raml.fromRaml(js.as[raml.Group]))
    }

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
  implicit lazy val RemoveHealthCheckReads: Reads[RemoveHealthCheck] = Json.reads[RemoveHealthCheck]
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
}
