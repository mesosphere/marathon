package mesosphere.marathon.api.v2.json

import java.lang.{ Double => JDouble }

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.event._
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state._
import mesosphere.marathon.state.Container.{ Volume, Docker }
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.upgrade._
import play.api.data.validation.ValidationError
import play.api.libs.json._
import play.api.libs.functional.syntax._
import org.apache.mesos.{ Protos => mesos }
import mesos.ContainerInfo.DockerInfo.Network

import scala.collection.immutable.Seq
import scala.concurrent.duration._

object Formats {

  implicit val IntegerFormat: Format[Integer] = __.format[Int].inmap(Int.box, Int.unbox)
  implicit val DoubleFormat: Format[JDouble] = __.format[Double].inmap(Double.box, Double.unbox)

  implicit val CommandFormat: Format[Command] = Json.format[Command]

  /*
   * HealthCheck related formats
   */

  implicit val ProtocolFormat: Format[Protocol] =
    enumFormat(Protocol.valueOf, str => s"$str is not a valid protocol")

  implicit val HealtCheckFormat: Format[HealthCheck] = {
    import HealthCheck._

    (
      (__ \ "path").formatNullable[Option[String]].withDefault(DefaultPath) ~
      (__ \ "protocol").formatNullable[Protocol].withDefault(DefaultProtocol) ~
      (__ \ "portIndex").formatNullable[Integer].withDefault(DefaultPortIndex) ~
      (__ \ "command").formatNullable[Command] ~
      (__ \ "gracePeriodSeconds").formatNullable[Long].withDefault(DefaultGracePeriod.toSeconds).asSeconds ~
      (__ \ "intervalSeconds").formatNullable[Long].withDefault(DefaultInterval.toSeconds).asSeconds ~
      (__ \ "timeoutSeconds").formatNullable[Long].withDefault(DefaultTimeout.toSeconds).asSeconds ~
      (__ \ "maxConsecutiveFailures").formatNullable[Integer].withDefault(DefaultMaxConsecutiveFailures)
    )(HealthCheck.apply, unlift(HealthCheck.unapply))
  }

  /*
   * Container related formats
   */

  implicit val NetworkFormat: Format[Network] =
    enumFormat(Network.valueOf, str => s"$str is not a valid network type")

  implicit val PortMappingFormat: Format[Docker.PortMapping] = (
    (__ \ "containerPort").format[Integer] ~
    (__ \ "hostPort").formatNullable[Integer].withDefault(0) ~
    (__ \ "servicePort").formatNullable[Integer].withDefault(0) ~
    (__ \ "protocol").formatNullable[String].withDefault("tcp")
  )(PortMapping(_, _, _, _), unlift(PortMapping.unapply))

  implicit val DockerFormat: Format[Docker] = (
    (__ \ "image").format[String] ~
    (__ \ "network").formatNullable[Network] ~
    (__ \ "portMappings").formatNullable[Seq[Docker.PortMapping]]
  )(Docker(_, _, _), unlift(Docker.unapply))

  implicit val ModeFormat: Format[mesos.Volume.Mode] =
    enumFormat(mesos.Volume.Mode.valueOf, str => s"$str is not a valid mode")

  implicit val VolumeFormat: Format[Volume] = (
    (__ \ "containerPath").format[String] ~
    (__ \ "hostPath").format[String] ~
    (__ \ "mode").format[mesos.Volume.Mode]
  )(Volume(_, _, _), unlift(Volume.unapply))

  implicit val ContainerTypeFormat: Format[mesos.ContainerInfo.Type] =
    enumFormat(mesos.ContainerInfo.Type.valueOf, str => s"$str is not a valid container type")

  implicit val ContainerFormat: Format[Container] = (
    (__ \ "type").formatNullable[mesos.ContainerInfo.Type].withDefault(mesos.ContainerInfo.Type.DOCKER) ~
    (__ \ "volumes").formatNullable[Seq[Volume]].withDefault(Nil) ~
    (__ \ "docker").formatNullable[Docker]
  )(Container(_, _, _), unlift(Container.unapply))

  /*
   * AppDefinition related formats
   */

  implicit val TimestampFormat: Format[Timestamp] = __.format[String].inmap(Timestamp(_), _.toString)

  implicit val UpgradeStrategyFormat: Format[UpgradeStrategy] = Json.format[UpgradeStrategy]
  implicit val PathIdFormat: Format[PathId] = __.format[String].inmap(PathId(_), _.toString)
  implicit val ConstraintFormat: Format[Constraint] = __.format[Seq[String]].inmap(
    seq => {
      val builder = Constraint
        .newBuilder()
        .setField(seq(0))
        .setOperator(Operator.valueOf(seq(1)))
      if (seq.size == 3) builder.setValue(seq(2))
      builder.build()
    },
    constraint => {
      val builder = Seq.newBuilder[String]
      builder += constraint.getField
      builder += constraint.getOperator.name
      if (constraint.hasValue) builder += constraint.getValue
      builder.result()
    }
  )

  implicit val AppDefinitionFormat: Reads[AppDefinition] = {
    import AppDefinition._

    (
      (__ \ "id").read[PathId] ~
      (__ \ "cmd").readNullable[String] ~
      (__ \ "args").readNullable[Seq[String]] ~
      (__ \ "user").readNullable[String] ~
      (__ \ "env").readNullable[Map[String, String]].withDefault(Map.empty) ~
      (__ \ "instances").readNullable[Integer](minValue(0)).withDefault(DefaultInstances) ~
      (__ \ "cpus").readNullable[JDouble].withDefault(DefaultCpus) ~
      (__ \ "mem").readNullable[JDouble].withDefault(DefaultMem) ~
      (__ \ "disk").readNullable[JDouble].withDefault(DefaultDisk) ~
      (__ \ "executor").readNullable[String](regex("^(//cmd)|(/?[^/]+(/[^/]+)*)|$")).withDefault("") ~
      (__ \ "constraints").readNullable[Set[Constraint]].withDefault(Set.empty) ~
      (__ \ "uris").readNullable[Seq[String]].withDefault(Nil) ~
      (__ \ "storeUrls").readNullable[Seq[String]].withDefault(Nil) ~
      (__ \ "ports").readNullable[Seq[Integer]](uniquePorts).withDefault(Nil) ~
      (__ \ "requirePorts").readNullable[Boolean].withDefault(DefaultRequirePorts) ~
      (__ \ "backoffSeconds").readNullable[Long].withDefault(DefaultBackoff.toSeconds).asSeconds ~
      (__ \ "backoffFactor").readNullable[Double].withDefault(DefaultBackoffFactor) ~
      (__ \ "container").readNullable[Container] ~
      (__ \ "healthChecks").readNullable[Set[HealthCheck]].withDefault(Set.empty) ~
      (__ \ "dependencies").readNullable[Set[PathId]].withDefault(Set.empty) ~
      (__ \ "upgradeStrategy").readNullable[UpgradeStrategy].withDefault(UpgradeStrategy.empty)
    )(AppDefinition(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)).flatMap { app =>
        // necessary because of case class limitations
        (__ \ "version").readNullable[Timestamp].withDefault(Timestamp.now()).map { v =>
          app.copy(version = v)
        }
      }
  }

  implicit val AppDefinitionWrites = {
    implicit val durationWrites = Writes[FiniteDuration] { d =>
      JsNumber(d.toSeconds)
    }

    import Json.toJson

    Writes[AppDefinition] { app =>
      Json.obj(
        "id" -> toJson(app.id),
        "cmd" -> toJson(app.cmd),
        "args" -> toJson(app.args),
        "user" -> toJson(app.user),
        "env" -> toJson(app.env),
        "instances" -> toJson(app.instances),
        "cpus" -> toJson(app.cpus),
        "mem" -> toJson(app.mem),
        "disk" -> toJson(app.disk),
        "executor" -> toJson(app.executor),
        "constraints" -> toJson(app.constraints),
        "uris" -> toJson(app.uris),
        "storeUrls" -> toJson(app.storeUrls),
        "ports" -> toJson(app.ports),
        "requirePorts" -> toJson(app.requirePorts),
        "backoffSeconds" -> toJson(app.backoff),
        "backoffFactor" -> toJson(app.backoffFactor),
        "container" -> toJson(app.container),
        "healthChecks" -> toJson(app.healthChecks),
        "dependencies" -> toJson(app.dependencies),
        "upgradeStrategy" -> toJson(app.upgradeStrategy),
        "version" -> toJson(app.version)
      )
    }
  }

  /*
   * Deployment related formats
   */

  implicit val ByteArrayFormat: Format[Array[Byte]] =
    __.format[Seq[Int]].inmap(_.map(_.toByte).toArray, _.to[Seq].map(_.toInt))
  implicit val GroupFormat: Format[Group] = Json.format[Group]
  implicit val URLToStringMapFormat: Format[Map[java.net.URL, String]] = __.format[Map[String, String]]
    .inmap(
      _.map { case (k, v) => new java.net.URL(k) -> v },
      _.map { case (k, v) => k.toString -> v }
    )
  implicit val DeploymentActionWrites: Writes[DeploymentAction] = Writes { action =>
    Json.obj(
      "type" -> action.getClass.getSimpleName,
      "app" -> action.app.id
    )
  }

  implicit val DeploymentStepWrites: Writes[DeploymentStep] = Json.writes[DeploymentStep]
  implicit val DeploymentPlanWrites: Writes[DeploymentPlan] = (
    (__ \ "id").write[String] ~
    (__ \ "original").write[Group] ~
    (__ \ "target").write[Group] ~
    (__ \ "steps").write[List[DeploymentStep]] ~
    (__ \ "version").write[Timestamp]
  )(unlift(DeploymentPlan.unapply))

  /*
   * Event related formats
   */

  implicit val AppTerminatedEventWrites: Writes[AppTerminatedEvent] = Json.writes[AppTerminatedEvent]
  implicit val ApiPostEventWrites: Writes[ApiPostEvent] = Json.writes[ApiPostEvent]
  implicit val SubscribeWrites: Writes[Subscribe] = Json.writes[Subscribe]
  implicit val UnsubscribeWrites: Writes[Unsubscribe] = Json.writes[Unsubscribe]
  implicit val AddHealthCheckWrites: Writes[AddHealthCheck] = Json.writes[AddHealthCheck]
  implicit val RemoveHealthCheckWrites: Writes[RemoveHealthCheck] = Json.writes[RemoveHealthCheck]
  implicit val FailedHealthCheckWrites: Writes[FailedHealthCheck] = Json.writes[FailedHealthCheck]
  implicit val HealthStatusChangedWrites: Writes[HealthStatusChanged] = Json.writes[HealthStatusChanged]
  implicit val GroupChangeSuccessWrites: Writes[GroupChangeSuccess] = Json.writes[GroupChangeSuccess]
  implicit val GroupChangeFailedWrites: Writes[GroupChangeFailed] = Json.writes[GroupChangeFailed]
  implicit val DeploymentSuccessWrites: Writes[DeploymentSuccess] = Json.writes[DeploymentSuccess]
  implicit val DeploymentFailedWrites: Writes[DeploymentFailed] = Json.writes[DeploymentFailed]
  implicit val DeploymentStatusWrites: Writes[DeploymentStatus] = Json.writes[DeploymentStatus]
  implicit val DeploymentStepSuccessWrites: Writes[DeploymentStepSuccess] = Json.writes[DeploymentStepSuccess]
  implicit val DeploymentStepFailureWrites: Writes[DeploymentStepFailure] = Json.writes[DeploymentStepFailure]
  implicit val MesosStatusUpdateEventWrites: Writes[MesosStatusUpdateEvent] = Json.writes[MesosStatusUpdateEvent]
  implicit val MesosFrameworkMessageEventWrites: Writes[MesosFrameworkMessageEvent] =
    Json.writes[MesosFrameworkMessageEvent]

  /*
   * Helpers
   */

  def uniquePorts: Reads[Seq[Integer]] = __.read[Seq[Integer]].filter { ports =>
    val withoutRandom = ports.filterNot(_ == AppDefinition.RandomPortValue)
    withoutRandom.distinct.size == withoutRandom.size
  }

  def minValue[A](min: A)(implicit O: Ordering[A], reads: Reads[A]): Reads[A] =
    Reads.filterNot[A](ValidationError(s"value must not be less than $min"))(x => O.lt(x, min))(reads)

  def regex(pattern: String): Reads[String] =
    Reads.filter[String](ValidationError("invalid value"))(_.matches(pattern))

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

  private def enumFormat[A <: java.lang.Enum[A]](read: String => A, errorMsg: String => String): Format[A] = {
    val reads = Reads[A] {
      case JsString(str) =>
        try {
          JsSuccess(read(str))
        }
        catch {
          case _: IllegalArgumentException => JsError(errorMsg(str))
        }

      case x => JsError(s"expected string, got $x")
    }

    val writes = Writes[A] { a: A => JsString(a.name) }

    Format(reads, writes)
  }
}
