package mesosphere.marathon
package api.v2.json

import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.deployment.{ DeploymentAction, DeploymentPlan, DeploymentStep, DeploymentStepInfo }
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.plugin.{ PluginDefinition, PluginDefinitions }
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.readiness.{ HttpResponse, ReadinessCheckResult }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => mesos }
import play.api.data.validation.ValidationError
import play.api.libs.functional.syntax._
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json._

import scala.concurrent.duration._

// TODO: We should replace this entire thing with the auto-generated formats from the RAML.
// See https://mesosphere.atlassian.net/browse/MARATHON-1291
// https://mesosphere.atlassian.net/browse/MARATHON-1292
object Formats extends Formats {

  implicit class ReadsWithDefault[A](val reads: Reads[Option[A]]) extends AnyVal {
    def withDefault(a: A): Reads[A] = reads.map(_.getOrElse(a))
  }

  implicit class FormatWithDefault[A](val m: OFormat[Option[A]]) extends AnyVal {
    def withDefault(a: A): OFormat[A] = m.inmap(_.getOrElse(a), Some(_))
  }

  implicit class ReadsAsSeconds(val reads: Reads[Long]) extends AnyVal {
    def asSeconds: Reads[FiniteDuration] = reads.map(_.seconds)
  }

  implicit class FormatAsSeconds(val format: OFormat[Long]) extends AnyVal {
    def asSeconds: OFormat[FiniteDuration] =
      format.inmap(
        _.seconds,
        _.toSeconds
      )
  }
}

trait Formats
    extends AppAndGroupFormats
    with HealthCheckFormats
    with ReadinessCheckFormats
    with DeploymentFormats
    with EventFormats
    with EventSubscribersFormats
    with PluginFormats {

  implicit lazy val TaskFailureWrites: Writes[TaskFailure] = Writes { failure =>
    Json.obj(
      "appId" -> failure.appId,
      "host" -> failure.host,
      "message" -> failure.message,
      "state" -> failure.state.name(),
      "taskId" -> failure.taskId.getValue,
      "timestamp" -> failure.timestamp,
      "version" -> failure.version,
      "slaveId" -> failure.slaveId.fold[JsValue](JsNull){ slaveId => JsString(slaveId.getValue) }
    )
  }

  implicit lazy val networkInfoProtocolWrites = Writes[mesos.NetworkInfo.Protocol] { protocol =>
    JsString(protocol.name)
  }

  private[this] val allowedProtocolString =
    mesos.NetworkInfo.Protocol.values().toSeq.map(_.getDescriptorForType.getName).mkString(", ")

  implicit lazy val networkInfoProtocolReads = Reads[mesos.NetworkInfo.Protocol] { json =>
    json.validate[String].flatMap { protocolString: String =>

      Option(mesos.NetworkInfo.Protocol.valueOf(protocolString)) match {
        case Some(protocol) => JsSuccess(protocol)
        case None =>
          JsError(s"'$protocolString' is not a valid protocol. Allowed values: $allowedProtocolString")
      }

    }
  }

  implicit lazy val ipAddressFormat: Format[mesos.NetworkInfo.IPAddress] = {
    def toIpAddress(ipAddress: String, protocol: mesos.NetworkInfo.Protocol): mesos.NetworkInfo.IPAddress =
      mesos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ipAddress).setProtocol(protocol).build()

    def toTuple(ipAddress: mesos.NetworkInfo.IPAddress): (String, mesos.NetworkInfo.Protocol) =
      (ipAddress.getIpAddress, ipAddress.getProtocol)

    (
      (__ \ "ipAddress").format[String] ~
      (__ \ "protocol").format[mesos.NetworkInfo.Protocol]
    )(toIpAddress, toTuple)
  }

  implicit lazy val InstanceIdWrite: Writes[Instance.Id] = Writes { id => JsString(id.idString) }
  implicit lazy val TaskStateFormat: Format[mesos.TaskState] =
    enumFormat(mesos.TaskState.valueOf, str => s"$str is not a valid TaskState type")

  implicit val TaskStatusNetworkInfoFormat: Format[NetworkInfo] = (
    (__ \ "hostName").format[String] ~
    (__ \ "hostPorts").format[Seq[Int]] ~
    (__ \ "ipAddresses").format[Seq[mesos.NetworkInfo.IPAddress]]
  )(NetworkInfo(_, _, _), unlift(NetworkInfo.unapply))

  import scala.collection.mutable
  implicit val TaskWrites: Writes[Task] = Writes { task =>
    val fields = mutable.HashMap[String, JsValueWrapper](
      "id" -> task.taskId,
      "state" -> Condition.toMesosTaskStateOrStaging(task.status.condition)
    )
    if (task.isActive) {
      fields.update("startedAt", task.status.startedAt)
      fields.update("stagedAt", task.status.stagedAt)
      fields.update("ports", task.status.networkInfo.hostPorts)
      fields.update("version", task.runSpecVersion)
    }
    if (task.status.networkInfo.ipAddresses.nonEmpty) {
      fields.update("ipAddresses", task.status.networkInfo.ipAddresses)
    }
    task.reservationWithVolumes.foreach { reservation =>
      fields.update("localVolumes", reservation.volumeIds)
    }

    Json.obj(fields.to[Seq]: _*)
  }

  implicit lazy val EnrichedTaskWrites: Writes[EnrichedTask] = Writes { task =>
    val taskJson = TaskWrites.writes(task.task).as[JsObject]

    val enrichedJson = taskJson ++ Json.obj(
      "appId" -> task.appId,
      "slaveId" -> task.agentInfo.agentId,
      "host" -> task.agentInfo.host
    )

    val withServicePorts = if (task.servicePorts.nonEmpty)
      enrichedJson ++ Json.obj("servicePorts" -> task.servicePorts)
    else
      enrichedJson

    if (task.healthCheckResults.nonEmpty)
      withServicePorts ++ Json.obj("healthCheckResults" -> task.healthCheckResults)
    else
      withServicePorts
  }

  implicit lazy val PathIdFormat: Format[PathId] = Format(
    Reads.of[String](Reads.minLength[String](1)).map(PathId(_)),
    Writes[PathId] { id => JsString(id.toString) }
  )

  implicit lazy val InstanceIdFormat: Format[Instance.Id] = Format(
    Reads.of[String](Reads.minLength[String](3)).map(Instance.Id(_)),
    Writes[Instance.Id] { id => JsString(id.idString) }
  )

  implicit lazy val TimestampFormat: Format[Timestamp] = Format(
    Reads.of[String].map(Timestamp(_)),
    Writes[Timestamp] { t => JsString(t.toString) }
  )

  /*
   * Helpers
   */

  def nonEmpty[C <: Iterable[_]](implicit reads: Reads[C]): Reads[C] =
    Reads.filterNot[C](ValidationError("set must not be empty"))(_.isEmpty)(reads)

  def enumFormat[A <: java.lang.Enum[A]](read: String => A, errorMsg: String => String): Format[A] = {
    val reads = Reads[A] {
      case JsString(str) =>
        try {
          JsSuccess(read(str))
        } catch {
          case _: IllegalArgumentException => JsError(errorMsg(str))
        }

      case x: JsValue => JsError(s"expected string, got $x")
    }

    val writes = Writes[A] { a: A => JsString(a.name) }

    Format(reads, writes)
  }
}

trait DeploymentFormats {
  import Formats._

  implicit lazy val ByteArrayFormat: Format[Array[Byte]] =
    Format(
      Reads.of[Seq[Int]].map(_.map(_.toByte).toArray),
      Writes { xs =>
        JsArray(xs.to[Seq].map(b => JsNumber(b.toInt)))
      }
    )

  implicit lazy val URLToStringMapFormat: Format[Map[java.net.URL, String]] = Format(
    Reads.of[Map[String, String]]
      .map(
        _.map { case (k, v) => new java.net.URL(k) -> v }
      ),
    Writes[Map[java.net.URL, String]] { m =>
      Json.toJson(m)
    }
  )

  def actionInstanceOn(runSpec: RunSpec): String = runSpec match {
    case _: AppDefinition => "app"
    case _: PodDefinition => "pod"
  }

  implicit lazy val DeploymentActionWrites: Writes[DeploymentAction] = Writes { action =>
    Json.obj(
      "action" -> DeploymentAction.actionName(action),
      actionInstanceOn(action.runSpec) -> action.runSpec.id
    )
  }

  implicit lazy val DeploymentStepWrites: Writes[DeploymentStep] = Json.writes[DeploymentStep]

  implicit lazy val DeploymentStepInfoWrites: Writes[DeploymentStepInfo] = Writes { info =>
    def currentAction(action: DeploymentAction): JsObject = Json.obj (
      "action" -> DeploymentAction.actionName(action),
      actionInstanceOn(action.runSpec) -> action.runSpec.id,
      "readinessCheckResults" -> info.readinessChecksByApp(action.runSpec.id)
    )
    Json.obj(
      "id" -> info.plan.id,
      "version" -> info.plan.version,
      "affectedApps" -> info.plan.affectedAppIds,
      "affectedPods" -> info.plan.affectedPodIds,
      "steps" -> info.plan.steps,
      "currentActions" -> info.step.actions.map(currentAction),
      "currentStep" -> info.stepIndex,
      "totalSteps" -> info.plan.steps.size
    )
  }
}

trait EventFormats {
  import Formats._

  implicit lazy val AppTerminatedEventWrites: Writes[AppTerminatedEvent] = Json.writes[AppTerminatedEvent]

  implicit lazy val PodEventWrites: Writes[PodEvent] = Writes { event =>
    Json.obj(
      "clientIp" -> event.clientIp,
      "uri" -> event.uri,
      "eventType" -> event.eventType,
      "timestamp" -> event.timestamp
    )
  }

  implicit lazy val ApiPostEventWrites: Writes[ApiPostEvent] = Writes { event =>
    Json.obj(
      "clientIp" -> event.clientIp,
      "uri" -> event.uri,
      "appDefinition" -> event.appDefinition,
      "eventType" -> event.eventType,
      "timestamp" -> event.timestamp
    )
  }

  implicit lazy val DeploymentPlanWrites: Writes[DeploymentPlan] = Writes { plan =>

    Json.obj(
      "id" -> plan.id,
      "original" -> Raml.toRaml[Group, raml.Group](plan.original),
      "target" -> Raml.toRaml[Group, raml.Group](plan.target),
      "steps" -> plan.steps,
      "version" -> plan.version
    )
  }

  implicit lazy val SubscribeWrites: Writes[Subscribe] = Json.writes[Subscribe]
  implicit lazy val UnsubscribeWrites: Writes[Unsubscribe] = Json.writes[Unsubscribe]
  implicit lazy val UnhealthyInstanceKillEventWrites: Writes[UnhealthyInstanceKillEvent] = Json.writes[UnhealthyInstanceKillEvent]
  implicit lazy val EventStreamAttachedWrites: Writes[EventStreamAttached] = Json.writes[EventStreamAttached]
  implicit lazy val EventStreamDetachedWrites: Writes[EventStreamDetached] = Json.writes[EventStreamDetached]

  implicit lazy val AddHealthCheckWrites: Writes[AddHealthCheck] = Writes { check =>
    Json.obj(
      "appId" -> check.appId,
      "eventType" -> check.eventType,
      "healthCheck" -> Raml.toRaml(check.healthCheck),
      "timestamp" -> check.timestamp,
      "version" -> check.version
    )
  }

  implicit lazy val RemoveHealthCheckWrites: Writes[RemoveHealthCheck] = Json.writes[RemoveHealthCheck]

  implicit lazy val FailedHealthCheckWrites: Writes[FailedHealthCheck] = Writes { check =>
    Json.obj(
      "appId" -> check.appId,
      "eventType" -> check.eventType,
      "healthCheck" -> Raml.toRaml(check.healthCheck),
      "taskId" -> check.instanceId,
      "timestamp" -> check.timestamp
    )
  }

  implicit lazy val HealthStatusChangedWrites: Writes[HealthStatusChanged] = Json.writes[HealthStatusChanged]
  implicit lazy val GroupChangeSuccessWrites: Writes[GroupChangeSuccess] = Json.writes[GroupChangeSuccess]
  implicit lazy val GroupChangeFailedWrites: Writes[GroupChangeFailed] = Json.writes[GroupChangeFailed]
  implicit lazy val DeploymentSuccessWrites: Writes[DeploymentSuccess] = Json.writes[DeploymentSuccess]
  implicit lazy val DeploymentFailedWrites: Writes[DeploymentFailed] = Json.writes[DeploymentFailed]
  implicit lazy val DeploymentStatusWrites: Writes[DeploymentStatus] = Json.writes[DeploymentStatus]
  implicit lazy val DeploymentStepSuccessWrites: Writes[DeploymentStepSuccess] = Json.writes[DeploymentStepSuccess]
  implicit lazy val DeploymentStepFailureWrites: Writes[DeploymentStepFailure] = Json.writes[DeploymentStepFailure]
  implicit lazy val MesosStatusUpdateEventWrites: Writes[MesosStatusUpdateEvent] = Json.writes[MesosStatusUpdateEvent]
  implicit lazy val MesosFrameworkMessageEventWrites: Writes[MesosFrameworkMessageEvent] =
    Json.writes[MesosFrameworkMessageEvent]
  implicit lazy val SchedulerDisconnectedEventWrites: Writes[SchedulerDisconnectedEvent] =
    Json.writes[SchedulerDisconnectedEvent]
  implicit lazy val SchedulerRegisteredEventWritesWrites: Writes[SchedulerRegisteredEvent] =
    Json.writes[SchedulerRegisteredEvent]
  implicit lazy val SchedulerReregisteredEventWritesWrites: Writes[SchedulerReregisteredEvent] =
    Json.writes[SchedulerReregisteredEvent]
  implicit lazy val InstanceChangedEventWrites: Writes[InstanceChanged] = Writes { change =>
    Json.obj(
      "instanceId" -> change.id,
      "condition" -> change.condition.toString,
      "runSpecId" -> change.runSpecId,
      "agentId" -> change.instance.agentInfo.agentId,
      "host" -> change.instance.agentInfo.host,
      "runSpecVersion" -> change.runSpecVersion,
      "timestamp" -> change.timestamp,
      "eventType" -> change.eventType
    )
  }
  implicit lazy val InstanceHealthChangedEventWrites: Writes[InstanceHealthChanged] = Writes { change =>
    Json.obj(
      "instanceId" -> change.id,
      "runSpecId" -> change.runSpecId,
      "healthy" -> change.healthy,
      "runSpecVersion" -> change.runSpecVersion,
      "timestamp" -> change.timestamp,
      "eventType" -> change.eventType
    )
  }
  implicit lazy val UnknownInstanceTerminatedEventWrites: Writes[UnknownInstanceTerminated] = Writes { change =>
    Json.obj(
      "instanceId" -> change.id,
      "runSpecId" -> change.runSpecId,
      "condition" -> change.condition.toString,
      "timestamp" -> change.timestamp,
      "eventType" -> change.eventType
    )
  }

  def eventToJson(event: MarathonEvent): JsValue = event match {
    case event: AppTerminatedEvent => Json.toJson(event)
    case event: ApiPostEvent => Json.toJson(event)
    case event: Subscribe => Json.toJson(event)
    case event: Unsubscribe => Json.toJson(event)
    case event: EventStreamAttached => Json.toJson(event)
    case event: EventStreamDetached => Json.toJson(event)
    case event: AddHealthCheck => Json.toJson(event)
    case event: RemoveHealthCheck => Json.toJson(event)
    case event: FailedHealthCheck => Json.toJson(event)
    case event: HealthStatusChanged => Json.toJson(event)
    case event: UnhealthyInstanceKillEvent => Json.toJson(event)
    case event: GroupChangeSuccess => Json.toJson(event)
    case event: GroupChangeFailed => Json.toJson(event)
    case event: DeploymentSuccess => Json.toJson(event)
    case event: DeploymentFailed => Json.toJson(event)
    case event: DeploymentStatus => Json.toJson(event)
    case event: DeploymentStepSuccess => Json.toJson(event)
    case event: DeploymentStepFailure => Json.toJson(event)
    case event: MesosStatusUpdateEvent => Json.toJson(event)
    case event: MesosFrameworkMessageEvent => Json.toJson(event)
    case event: SchedulerDisconnectedEvent => Json.toJson(event)
    case event: SchedulerRegisteredEvent => Json.toJson(event)
    case event: SchedulerReregisteredEvent => Json.toJson(event)
    case event: InstanceChanged => Json.toJson(event)
    case event: InstanceHealthChanged => Json.toJson(event)
    case event: UnknownInstanceTerminated => Json.toJson(event)
    case event: PodEvent => Json.toJson(event)
  }
}

trait EventSubscribersFormats {

  implicit lazy val EventSubscribersWrites: Writes[EventSubscribers] = Writes { eventSubscribers =>
    Json.obj(
      "callbackUrls" -> eventSubscribers.urls
    )
  }
}

trait HealthCheckFormats {

  import Formats._

  implicit lazy val HealthWrites: Writes[Health] = Writes { health =>
    Json.obj(
      "alive" -> health.alive,
      "consecutiveFailures" -> health.consecutiveFailures,
      "firstSuccess" -> health.firstSuccess,
      "lastFailure" -> health.lastFailure,
      "lastSuccess" -> health.lastSuccess,
      "lastFailureCause" -> health.lastFailureCause.fold[JsValue](JsNull)(JsString),
      "instanceId" -> health.instanceId
    )
  }
}

trait ReadinessCheckFormats {
  implicit lazy val ReadinessCheckHttpResponseFormat: Format[HttpResponse] = Json.format[HttpResponse]
  implicit lazy val ReadinessCheckResultFormat: Format[ReadinessCheckResult] = Json.format[ReadinessCheckResult]
}

@SuppressWarnings(Array("PartialFunctionInsteadOfMatch"))
trait AppAndGroupFormats {

  import Formats._

  implicit lazy val IdentifiableWrites = Json.writes[Identifiable]

  implicit lazy val RunSpecWrites: Writes[RunSpec] = {
    Writes[RunSpec] {
      case app: AppDefinition => Json.toJson(Raml.toRaml(app))
      case pod: PodDefinition => Json.toJson(Raml.toRaml(pod))
    }
  }

  implicit lazy val TaskCountsWrites: Writes[TaskCounts] =
    Writes { counts =>
      Json.obj(
        "tasksStaged" -> counts.tasksStaged,
        "tasksRunning" -> counts.tasksRunning,
        "tasksHealthy" -> counts.tasksHealthy,
        "tasksUnhealthy" -> counts.tasksUnhealthy
      )
    }

  lazy val TaskCountsWritesWithoutPrefix: Writes[TaskCounts] =
    Writes { counts =>
      Json.obj(
        "staged" -> counts.tasksStaged,
        "running" -> counts.tasksRunning,
        "healthy" -> counts.tasksHealthy,
        "unhealthy" -> counts.tasksUnhealthy
      )
    }

  implicit lazy val TaskLifeTimeWrites: Writes[TaskLifeTime] =
    Writes { lifeTime =>
      Json.obj(
        "averageSeconds" -> lifeTime.averageSeconds,
        "medianSeconds" -> lifeTime.medianSeconds
      )
    }

  implicit lazy val TaskStatsWrites: Writes[TaskStats] =
    Writes { stats =>
      val statsJson = Json.obj("counts" -> TaskCountsWritesWithoutPrefix.writes(stats.counts))
      Json.obj(
        "stats" -> stats.maybeLifeTime.fold(ifEmpty = statsJson)(lifeTime =>
          statsJson ++ Json.obj("lifeTime" -> lifeTime)
        )
      )
    }

  @SuppressWarnings(Array("PartialFunctionInsteadOfMatch"))
  implicit lazy val TaskStatsByVersionWrites: Writes[TaskStatsByVersion] =
    Writes { byVersion =>
      val maybeJsons = Map[String, Option[TaskStats]](
        "startedAfterLastScaling" -> byVersion.maybeStartedAfterLastScaling,
        "withLatestConfig" -> byVersion.maybeWithLatestConfig,
        "withOutdatedConfig" -> byVersion.maybeWithOutdatedConfig,
        "totalSummary" -> byVersion.maybeTotalSummary
      )
      Json.toJson(
        maybeJsons.flatMap {
          case (k, v) => v.map(k -> TaskStatsWrites.writes(_))
        }
      )
    }

  implicit lazy val ExtendedAppInfoWrites: Writes[AppInfo] =
    Writes { info =>
      val appJson = RunSpecWrites.writes(info.app).as[JsObject]

      val maybeJson = Seq[Option[JsObject]](
        info.maybeCounts.map(TaskCountsWrites.writes(_).as[JsObject]),
        info.maybeDeployments.map(deployments => Json.obj("deployments" -> deployments)),
        info.maybeReadinessCheckResults.map(readiness => Json.obj("readinessCheckResults" -> readiness)),
        info.maybeTasks.map(tasks => Json.obj("tasks" -> tasks)),
        info.maybeLastTaskFailure.map(lastFailure => Json.obj("lastTaskFailure" -> lastFailure)),
        info.maybeTaskStats.map(taskStats => Json.obj("taskStats" -> taskStats))
      ).flatten

      maybeJson.foldLeft(appJson)((result, obj) => result ++ obj)
    }

  implicit lazy val GroupInfoWrites: Writes[GroupInfo] =
    Writes { info =>

      val maybeJson = Seq[Option[JsObject]](
        info.maybeApps.map(apps => Json.obj("apps" -> apps)),
        info.maybeGroups.map(groups => Json.obj("groups" -> groups)),
        info.maybePods.map(pods => Json.obj("pods" -> pods))
      ).flatten

      val groupJson = Json.obj (
        "id" -> info.group.id,
        "dependencies" -> info.group.dependencies,
        "version" -> info.group.version
      )

      maybeJson.foldLeft(groupJson)((result, obj) => result ++ obj)
    }

}

trait PluginFormats {

  implicit lazy val pluginDefinitionFormat: Writes[PluginDefinition] = (
    (__ \ "id").write[String] ~
    (__ \ "plugin").write[String] ~
    (__ \ "implementation").write[String] ~
    (__ \ "tags").writeNullable[Set[String]] ~
    (__ \ "info").writeNullable[JsObject]
  ) (d => (d.id, d.plugin, d.implementation, d.tags, d.info))

  implicit lazy val pluginDefinitionsFormat: Writes[PluginDefinitions] = Json.writes[PluginDefinitions]
}
