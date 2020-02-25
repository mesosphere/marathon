package mesosphere.marathon
package api.v2.json

import java.time.OffsetDateTime

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.datatype.jsr310.ser.OffsetDateTimeSerializer
import mesosphere.marathon.core.event._
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.plugin.{PluginDefinition, PluginDefinitions}
import mesosphere.marathon.core.readiness.{HttpResponse, ReadinessCheckResult}
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.raml.{Raml, RamlSerializer}
import mesosphere.marathon.state._
import org.apache.mesos.{Protos => mesos}
import play.api.libs.json.JsonValidationError
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration._
import scala.collection.compat._

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

  /**
    * Register Jackson serializer for RAML models.
    */
  def configureJacksonSerializer(): Unit = {

    // We want to serialize OffsetDateTime with our own format
    val s = new OffsetDateTimeSerializer(OffsetDateTimeSerializer.INSTANCE, false, Timestamp.formatter) {}

    val jtm = new JavaTimeModule()
    jtm.addSerializer(classOf[OffsetDateTime], s)

    RamlSerializer.serializer.registerModule(jtm)
    RamlSerializer.serializer.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    RamlSerializer.serializer.disable(SerializationFeature.FLUSH_AFTER_WRITE_VALUE)
  }
}

trait Formats
  extends HealthCheckFormats
  with ReadinessCheckFormats
  with DeploymentFormats
  with EventFormats
  with PluginFormats {

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

  def newPathIdWrites[O <: PathId]: Writes[O] = Writes[O] { id => JsString(id.toString) }
  implicit val PathIdReads: Reads[PathId] = Reads.of[String](Reads.minLength[String](1)).map(PathId(_))
  implicit val PathIdWrites: Writes[PathId] = newPathIdWrites[PathId]

  implicit val AbsolutePathIdReads: Reads[AbsolutePathId] = Reads.of[String](Reads.minLength[String](1)).map(AbsolutePathId(_))
  implicit val AbsolutePathIdWrites: Writes[AbsolutePathId] = newPathIdWrites[AbsolutePathId]

  implicit val TimestampFormat: Format[Timestamp] = Format(
    Reads.of[String].map(Timestamp(_)),
    Writes[Timestamp] { t => JsString(t.toString) }
  )

  /*
   * Helpers
   */

  def nonEmpty[C <: Iterable[_]](implicit reads: Reads[C]): Reads[C] =
    Reads.filterNot[C](JsonValidationError("set must not be empty"))(_.isEmpty)(reads)

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

  implicit lazy val ByteArrayFormat: Format[Array[Byte]] =
    Format(
      Reads.of[Seq[Int]].map(_.map(_.toByte).toArray),
      Writes { xs =>
        JsArray(xs.to(Seq).map(b => JsNumber(b.toInt)))
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
      "appDefinition" -> Raml.toRaml(event.appDefinition),
      "eventType" -> event.eventType,
      "timestamp" -> event.timestamp
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
      "agentId" -> change.instance.agentInfo.flatMap(_.agentId),
      "host" -> change.instance.hostname,
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
    case event: MesosStatusUpdateEvent => Json.toJson(event)
    case event: MesosFrameworkMessageEvent => Json.toJson(event)
    case event: SchedulerDisconnectedEvent => Json.toJson(event)
    case event: SchedulerRegisteredEvent => Json.toJson(event)
    case event: SchedulerReregisteredEvent => Json.toJson(event)
    case event: InstanceChanged => Json.toJson(event)
    case event: InstanceHealthChanged => Json.toJson(event)
    case event: UnknownInstanceTerminated => Json.toJson(event)
    case event: PodEvent => Json.toJson(event)

    case event: DeploymentSuccess => Json.toJson(event)
    case event: DeploymentFailed => Json.toJson(event)
    case event: DeploymentStatus => Json.toJson(event)
    case event: DeploymentStepSuccess => Json.toJson(event)
    case event: DeploymentStepFailure => Json.toJson(event)
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
