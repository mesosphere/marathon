package mesosphere.marathon.api.v2.json

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.Protos.ResidencyDefinition.TaskLostBehavior
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.plugin.{ PluginDefinition, PluginDefinitions }
import mesosphere.marathon.core.readiness.ReadinessCheck
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.event._
import mesosphere.marathon.event.http.EventSubscribers
import mesosphere.marathon.health.{ Health, HealthCheck }
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.DeploymentManager.DeploymentStepInfo
import mesosphere.marathon.upgrade._
import org.apache.mesos.Protos.ContainerInfo.DockerInfo
import org.apache.mesos.{ Protos => mesos }
import play.api.data.validation.ValidationError
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.duration._

// scalastyle:off file.size.limit
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
    with FetchUriFormats
    with ContainerFormats
    with DeploymentFormats
    with EventFormats
    with EventSubscribersFormats
    with PluginFormats
    with IpAddressFormats
    with SecretFormats {

  implicit lazy val TaskFailureWrites: Writes[TaskFailure] = Writes { failure =>
    Json.obj(
      "appId" -> failure.appId,
      "host" -> failure.host,
      "message" -> failure.message,
      "state" -> failure.state.name(),
      "taskId" -> failure.taskId.getValue,
      "timestamp" -> failure.timestamp,
      "version" -> failure.version,
      "slaveId" -> (if (failure.slaveId.isDefined) failure.slaveId.get.getValue else JsNull)
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

  implicit lazy val TaskIdWrite: Writes[Task.Id] = Writes { id => JsString(id.idString) }
  implicit lazy val LocalVolumeIdWrite: Writes[Task.LocalVolumeId] = Writes { id =>
    Json.obj(
      "containerPath" -> id.containerPath,
      "persistenceId" -> id.idString
    )
  }
  implicit lazy val TaskStateFormat: Format[mesos.TaskState] =
    enumFormat(mesos.TaskState.valueOf, str => s"$str is not a valid TaskState type")

  implicit lazy val TaskWrites: Writes[Task] = Writes { task =>
    val base = Json.obj(
      "id" -> task.taskId,
      "slaveId" -> task.agentInfo.agentId,
      "host" -> task.agentInfo.host,
      "state" -> task.mesosStatus.fold(mesos.TaskState.TASK_STAGING)(_.getState)
    )

    val launched = task.launched.map { launched =>
      launched.ipAddresses.foldLeft(
        base ++ Json.obj (
          "startedAt" -> launched.status.startedAt,
          "stagedAt" -> launched.status.stagedAt,
          "ports" -> launched.hostPorts,
          "version" -> launched.runSpecVersion
        )
      ){
          case (launchedJs, ipAddresses) => launchedJs ++ Json.obj("ipAddresses" -> ipAddresses)
        }
    }.getOrElse(base)

    val reservation = task.reservationWithVolumes.map { reservation =>
      launched ++ Json.obj(
        "localVolumes" -> reservation.volumeIds
      )
    }.getOrElse(launched)

    reservation
  }

  implicit lazy val EnrichedTaskWrites: Writes[EnrichedTask] = Writes { task =>
    val taskJson = TaskWrites.writes(task.task).as[JsObject]

    val enrichedJson = taskJson ++ Json.obj(
      "appId" -> task.appId
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

  implicit lazy val TaskIdFormat: Format[Task.Id] = Format(
    Reads.of[String](Reads.minLength[String](3)).map(Task.Id(_)),
    Writes[Task.Id] { id => JsString(id.idString) }
  )

  implicit lazy val TimestampFormat: Format[Timestamp] = Format(
    Reads.of[String].map(Timestamp(_)),
    Writes[Timestamp] { t => JsString(t.toString) }
  )

  implicit lazy val CommandFormat: Format[Command] = Json.format[Command]

  implicit lazy val ParameterFormat: Format[Parameter] = (
    (__ \ "key").format[String] ~
    (__ \ "value").format[String]
  )(Parameter(_, _), unlift(Parameter.unapply))

  /*
 * Helpers
 */

  def uniquePorts: Reads[Seq[Int]] = Format.of[Seq[Int]].filter(ValidationError("Ports must be unique.")) { ports =>
    val withoutRandom = ports.filterNot(_ == AppDefinition.RandomPortValue)
    withoutRandom.distinct.size == withoutRandom.size
  }

  def nonEmpty[C <: Iterable[_]](implicit reads: Reads[C]): Reads[C] =
    Reads.filterNot[C](ValidationError(s"set must not be empty"))(_.isEmpty)(reads)

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

trait ContainerFormats {
  import Formats._

  implicit lazy val DockerNetworkFormat: Format[DockerInfo.Network] =
    enumFormat(DockerInfo.Network.valueOf, str => s"$str is not a valid network type")

  implicit lazy val PortMappingFormat: Format[Docker.PortMapping] = (
    (__ \ "containerPort").formatNullable[Int].withDefault(AppDefinition.RandomPortValue) ~
    (__ \ "hostPort").formatNullable[Int] ~
    (__ \ "servicePort").formatNullable[Int].withDefault(AppDefinition.RandomPortValue) ~
    (__ \ "protocol").formatNullable[String].withDefault("tcp") ~
    (__ \ "name").formatNullable[String] ~
    (__ \ "labels").formatNullable[Map[String, String]].withDefault(Map.empty[String, String])
  )(PortMapping(_, _, _, _, _, _), unlift(PortMapping.unapply))

  implicit lazy val DockerFormat: Format[Docker] = (
    (__ \ "image").format[String] ~
    (__ \ "network").formatNullable[DockerInfo.Network] ~
    (__ \ "portMappings").formatNullable[Seq[Docker.PortMapping]] ~
    (__ \ "privileged").formatNullable[Boolean].withDefault(false) ~
    (__ \ "parameters").formatNullable[Seq[Parameter]].withDefault(Seq.empty) ~
    (__ \ "forcePullImage").formatNullable[Boolean].withDefault(false)
  )(Docker.withDefaultPortMappings(_, _, _, _, _, _), unlift(Docker.unapply))

  implicit lazy val ModeFormat: Format[mesos.Volume.Mode] =
    enumFormat(mesos.Volume.Mode.valueOf, str => s"$str is not a valid mde")

  implicit lazy val PersistentVolumeInfoFormat: Format[PersistentVolumeInfo] = Json.format[PersistentVolumeInfo]

  implicit lazy val ExternalVolumeInfoFormat: Format[ExternalVolumeInfo] = (
    (__ \ "size").formatNullable[Long] ~
    (__ \ "name").format[String] ~
    (__ \ "provider").format[String] ~
    (__ \ "options").formatNullable[Map[String, String]].withDefault(Map.empty[String, String])
  )(ExternalVolumeInfo(_, _, _, _), unlift(ExternalVolumeInfo.unapply))

  implicit lazy val VolumeFormat: Format[Volume] = (
    (__ \ "containerPath").format[String] ~
    (__ \ "hostPath").formatNullable[String] ~
    (__ \ "mode").format[mesos.Volume.Mode] ~
    (__ \ "persistent").formatNullable[PersistentVolumeInfo] ~
    (__ \ "external").formatNullable[ExternalVolumeInfo]
  )(Volume(_, _, _, _, _), unlift(Volume.unapply))

  implicit lazy val ContainerTypeFormat: Format[mesos.ContainerInfo.Type] =
    enumFormat(mesos.ContainerInfo.Type.valueOf, str => s"$str is not a valid container type")

  implicit lazy val ContainerFormat: Format[Container] = (
    (__ \ "type").formatNullable[mesos.ContainerInfo.Type].withDefault(mesos.ContainerInfo.Type.DOCKER) ~
    (__ \ "volumes").formatNullable[Seq[Volume]].withDefault(Nil) ~
    (__ \ "docker").formatNullable[Docker]
  )(Container(_, _, _), unlift(Container.unapply))
}

trait IpAddressFormats {
  import Formats._

  private[this] lazy val ValidPortProtocol: Reads[String] = {
    implicitly[Reads[String]]
      .filter(ValidationError("Invalid protocol. Only 'udp' or 'tcp' are allowed."))(
        DiscoveryInfo.Port.AllowedProtocols
      )
  }

  private[this] lazy val ValidPortName: Reads[String] = {
    implicitly[Reads[String]]
      .filter(ValidationError(s"Port name must fully match regular expression ${PortAssignment.PortNamePattern}"))(
        PortAssignment.PortNamePattern.pattern.matcher(_).matches()
      )
  }

  private[this] lazy val ValidPorts: Reads[Seq[DiscoveryInfo.Port]] = {
    def hasUniquePortNames(ports: Seq[DiscoveryInfo.Port]): Boolean = {
      ports.map(_.name).toSet.size == ports.size
    }

    def hasUniquePortNumberProtocol(ports: Seq[DiscoveryInfo.Port]): Boolean = {
      ports.map(port => (port.number, port.protocol)).toSet.size == ports.size
    }

    implicitly[Reads[Seq[DiscoveryInfo.Port]]]
      .filter(ValidationError("Port names are not unique."))(hasUniquePortNames)
      .filter(ValidationError("There may be only one port with a particular port number/protocol combination."))(
        hasUniquePortNumberProtocol
      )
  }

  implicit lazy val PortFormat: Format[DiscoveryInfo.Port] = (
    (__ \ "number").format[Int] ~
    (__ \ "name").format[String](ValidPortName) ~
    (__ \ "protocol").format[String](ValidPortProtocol) ~
    (__ \ "labels").formatNullable[Map[String, String]].withDefault(Map.empty[String, String])
  )(DiscoveryInfo.Port(_, _, _, _), unlift(DiscoveryInfo.Port.unapply))

  implicit lazy val DiscoveryInfoFormat: Format[DiscoveryInfo] = Format(
    (__ \ "ports").read[Seq[DiscoveryInfo.Port]](ValidPorts).map(DiscoveryInfo(_)),
    Writes[DiscoveryInfo] { discoveryInfo =>
      Json.obj("ports" -> discoveryInfo.ports.map(PortFormat.writes))
    }
  )

  implicit lazy val IpAddressFormat: Format[IpAddress] = (
    (__ \ "groups").formatNullable[Seq[String]].withDefault(Nil) ~
    (__ \ "labels").formatNullable[Map[String, String]].withDefault(Map.empty[String, String]) ~
    (__ \ "discovery").formatNullable[DiscoveryInfo].withDefault(DiscoveryInfo.empty) ~
    (__ \ "networkName").formatNullable[String]
  )(IpAddress(_, _, _, _), unlift(IpAddress.unapply))
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

  implicit lazy val GroupUpdateFormat: Format[GroupUpdate] = (
    (__ \ "id").formatNullable[PathId] ~
    (__ \ "apps").formatNullable[Set[AppDefinition]] ~
    (__ \ "groups").lazyFormatNullable(implicitly[Format[Set[GroupUpdate]]]) ~
    (__ \ "dependencies").formatNullable[Set[PathId]] ~
    (__ \ "scaleBy").formatNullable[Double] ~
    (__ \ "version").formatNullable[Timestamp]
  ) (GroupUpdate(_, _, _, _, _, _), unlift(GroupUpdate.unapply))

  implicit lazy val URLToStringMapFormat: Format[Map[java.net.URL, String]] = Format(
    Reads.of[Map[String, String]]
      .map(
        _.map { case (k, v) => new java.net.URL(k) -> v }
      ),
    Writes[Map[java.net.URL, String]] { m =>
      Json.toJson(m)
    }
  )

  implicit lazy val DeploymentActionWrites: Writes[DeploymentAction] = Writes { action =>
    Json.obj(
      "action" -> action.getClass.getSimpleName,
      "app" -> action.app.id
    )
  }

  implicit lazy val DeploymentStepWrites: Writes[DeploymentStep] = Json.writes[DeploymentStep]

  implicit lazy val DeploymentStepInfoWrites: Writes[DeploymentStepInfo] = Writes { info =>
    def currentAction(action: DeploymentAction): JsObject = Json.obj (
      "action" -> action.getClass.getSimpleName,
      "app" -> action.app.id,
      "readinessCheckResults" -> info.readinessChecksByApp(action.app.id)
    )
    Json.obj(
      "id" -> info.plan.id,
      "version" -> info.plan.version,
      "affectedApps" -> info.plan.affectedApplicationIds,
      "steps" -> info.plan.steps,
      "currentActions" -> info.step.actions.map(currentAction),
      "currentStep" -> info.nr,
      "totalSteps" -> info.plan.steps.size
    )
  }
}

trait EventFormats {
  import Formats._

  implicit lazy val AppTerminatedEventWrites: Writes[AppTerminatedEvent] = Json.writes[AppTerminatedEvent]

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
      "original" -> plan.original,
      "target" -> plan.target,
      "steps" -> plan.steps,
      "version" -> plan.version
    )
  }

  implicit lazy val SubscribeWrites: Writes[Subscribe] = Json.writes[Subscribe]
  implicit lazy val UnsubscribeWrites: Writes[Unsubscribe] = Json.writes[Unsubscribe]
  implicit lazy val UnhealthyTaskKillEventWrites: Writes[UnhealthyTaskKillEvent] = Json.writes[UnhealthyTaskKillEvent]
  implicit lazy val EventStreamAttachedWrites: Writes[EventStreamAttached] = Json.writes[EventStreamAttached]
  implicit lazy val EventStreamDetachedWrites: Writes[EventStreamDetached] = Json.writes[EventStreamDetached]
  implicit lazy val AddHealthCheckWrites: Writes[AddHealthCheck] = Json.writes[AddHealthCheck]
  implicit lazy val RemoveHealthCheckWrites: Writes[RemoveHealthCheck] = Json.writes[RemoveHealthCheck]
  implicit lazy val FailedHealthCheckWrites: Writes[FailedHealthCheck] = Json.writes[FailedHealthCheck]
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

  //scalastyle:off cyclomatic.complexity
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
    case event: UnhealthyTaskKillEvent => Json.toJson(event)
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
  }
  //scalastyle:on
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

  /*
   * HealthCheck related formats
   */

  implicit lazy val HealthWrites: Writes[Health] = Writes { health =>
    Json.obj(
      "alive" -> health.alive,
      "consecutiveFailures" -> health.consecutiveFailures,
      "firstSuccess" -> health.firstSuccess,
      "lastFailure" -> health.lastFailure,
      "lastSuccess" -> health.lastSuccess,
      "lastFailureCause" -> (if (health.lastFailureCause.isDefined) health.lastFailureCause.get else JsNull),
      "taskId" -> health.taskId
    )
  }

  implicit lazy val HealthCheckProtocolFormat: Format[Protocol] =
    enumFormat(Protocol.valueOf, str => s"$str is not a valid protocol")

  implicit lazy val HealthCheckFormat: Format[HealthCheck] = {
    import mesosphere.marathon.health.HealthCheck._

    (
      (__ \ "path").formatNullable[String] ~
      (__ \ "protocol").formatNullable[Protocol].withDefault(DefaultProtocol) ~
      (__ \ "portIndex").formatNullable[Int] ~
      (__ \ "command").formatNullable[Command] ~
      (__ \ "gracePeriodSeconds").formatNullable[Long].withDefault(DefaultGracePeriod.toSeconds).asSeconds ~
      (__ \ "intervalSeconds").formatNullable[Long].withDefault(DefaultInterval.toSeconds).asSeconds ~
      (__ \ "timeoutSeconds").formatNullable[Long].withDefault(DefaultTimeout.toSeconds).asSeconds ~
      (__ \ "maxConsecutiveFailures").formatNullable[Int].withDefault(DefaultMaxConsecutiveFailures) ~
      (__ \ "ignoreHttp1xx").formatNullable[Boolean].withDefault(DefaultIgnoreHttp1xx) ~
      (__ \ "port").formatNullable[Int]
    )(HealthCheck.apply, unlift(HealthCheck.unapply))
  }
}

trait ReadinessCheckFormats {
  import Formats._
  import mesosphere.marathon.core.readiness._

  implicit lazy val ReadinessCheckFormat: Format[ReadinessCheck] = {
    import ReadinessCheck._

    (
      (__ \ "name").formatNullable[String].withDefault(DefaultName) ~
      (__ \ "protocol").formatNullable[ReadinessCheck.Protocol].withDefault(DefaultProtocol) ~
      (__ \ "path").formatNullable[String].withDefault(DefaultPath) ~
      (__ \ "portName").formatNullable[String].withDefault(DefaultPortName) ~
      (__ \ "intervalSeconds").formatNullable[Long].withDefault(DefaultInterval.toSeconds).asSeconds ~
      (__ \ "timeoutSeconds").formatNullable[Long].withDefault(DefaultTimeout.toSeconds).asSeconds ~
      (__ \ "httpStatusCodesForReady").formatNullable[Set[Int]].withDefault(DefaultHttpStatusCodesForReady) ~
      (__ \ "preserveLastResponse").formatNullable[Boolean].withDefault(DefaultPreserveLastResponse)
    )(ReadinessCheck.apply, unlift(ReadinessCheck.unapply))
  }

  implicit lazy val ReadinessCheckProtocolFormat: Format[ReadinessCheck.Protocol] = {
    Format(
      Reads[ReadinessCheck.Protocol] {
        case JsString(string) =>
          StringToProtocol.get(string) match {
            case Some(protocol) => JsSuccess(protocol)
            case None => JsError(ProtocolErrorString)
          }
        case _: JsValue => JsError(ProtocolErrorString)
      },
      Writes[ReadinessCheck.Protocol](protocol => JsString(ProtocolToString(protocol)))
    )
  }
  implicit lazy val ReadinessCheckResultFormat: Format[ReadinessCheckResult] = Json.format[ReadinessCheckResult]
  implicit lazy val ReadinessCheckHttpResponseFormat: Format[HttpResponse] = Json.format[HttpResponse]

  private[this] val ProtocolToString = Map[ReadinessCheck.Protocol, String](
    ReadinessCheck.Protocol.HTTP -> "HTTP",
    ReadinessCheck.Protocol.HTTPS -> "HTTPS"
  )
  private[this] val StringToProtocol: Map[String, ReadinessCheck.Protocol] =
    ProtocolToString.map { case (k, v) => (v, k) }
  private[this] val ProtocolErrorString = s"Choose one of ${StringToProtocol.keys.mkString(", ")}"
}

trait FetchUriFormats {
  import Formats._

  implicit lazy val FetchUriFormat: Format[FetchUri] = {
    (
      (__ \ "uri").format[String] ~
      (__ \ "extract").formatNullable[Boolean].withDefault(true) ~
      (__ \ "executable").formatNullable[Boolean].withDefault(false) ~
      (__ \ "cache").formatNullable[Boolean].withDefault(false)
    )(FetchUri(_, _, _, _), unlift(FetchUri.unapply))
  }
}

trait SecretFormats {
  implicit lazy val SecretFormat = Json.format[Secret]
}

trait AppAndGroupFormats {

  import Formats._

  implicit lazy val IdentifiableWrites = Json.writes[Identifiable]

  implicit lazy val UpgradeStrategyWrites = Json.writes[UpgradeStrategy]
  implicit lazy val UpgradeStrategyReads: Reads[UpgradeStrategy] = {
    import mesosphere.marathon.state.AppDefinition._
    (
      (__ \ "minimumHealthCapacity").readNullable[Double].withDefault(DefaultUpgradeStrategy.minimumHealthCapacity) ~
      (__ \ "maximumOverCapacity").readNullable[Double].withDefault(DefaultUpgradeStrategy.maximumOverCapacity)
    ) (UpgradeStrategy(_, _))
  }

  implicit lazy val ConstraintFormat: Format[Constraint] = Format(
    new Reads[Constraint] {
      override def reads(json: JsValue): JsResult[Constraint] = {
        val validOperators = Operator.values().map(_.toString)

        json.asOpt[Seq[String]] match {
          case Some(seq) if seq.size >= 2 && seq.size <= 3 =>
            if (validOperators.contains(seq(1))) {
              val builder = Constraint.newBuilder().setField(seq(0)).setOperator(Operator.valueOf(seq(1)))
              if (seq.size == 3) builder.setValue(seq(2))
              JsSuccess(builder.build())
            } else {
              JsError(s"Constraint operator must be one of the following: [${validOperators.mkString(", ")}]")
            }
          case _ => JsError("Constraint definition must be an array of strings in format: <key>, <operator>[, value]")
        }
      }
    },
    Writes[Constraint] { constraint =>
      val builder = Seq.newBuilder[JsString]
      builder += JsString(constraint.getField)
      builder += JsString(constraint.getOperator.name)
      if (constraint.hasValue) builder += JsString(constraint.getValue)
      JsArray(builder.result())
    }
  )

  implicit lazy val EnvVarSecretRefFormat: Format[EnvVarSecretRef] = Json.format[EnvVarSecretRef]
  implicit lazy val EnvVarValueFormat: Format[EnvVarValue] = Format(
    new Reads[EnvVarValue] {
      override def reads(json: JsValue): JsResult[EnvVarValue] = {
        json.asOpt[String] match {
          case Some(stringValue) => JsSuccess(EnvVarString(stringValue))
          case _ => JsSuccess(json.as[EnvVarSecretRef])
        }
      }
    },
    new Writes[EnvVarValue] {
      override def writes(envvar: EnvVarValue): JsValue = {
        envvar match {
          case s: EnvVarString => JsString(s.value)
          case ref: EnvVarSecretRef => EnvVarSecretRefFormat.writes(ref)
        }
      }
    }
  )

  implicit lazy val AppDefinitionReads: Reads[AppDefinition] = {
    val executorPattern = "^(//cmd)|(/?[^/]+(/[^/]+)*)|$".r
    (
      (__ \ "id").read[PathId].filterNot(_.isRoot) ~
      (__ \ "cmd").readNullable[String](Reads.minLength(1)) ~
      (__ \ "args").readNullable[Seq[String]] ~
      (__ \ "user").readNullable[String] ~
      (__ \ "env").readNullable[Map[String, EnvVarValue]].withDefault(AppDefinition.DefaultEnv) ~
      (__ \ "instances").readNullable[Int].withDefault(AppDefinition.DefaultInstances) ~
      (__ \ "cpus").readNullable[Double].withDefault(AppDefinition.DefaultCpus) ~
      (__ \ "mem").readNullable[Double].withDefault(AppDefinition.DefaultMem) ~
      (__ \ "disk").readNullable[Double].withDefault(AppDefinition.DefaultDisk) ~
      (__ \ "executor").readNullable[String](Reads.pattern(executorPattern))
      .withDefault(AppDefinition.DefaultExecutor) ~
      (__ \ "constraints").readNullable[Set[Constraint]].withDefault(AppDefinition.DefaultConstraints) ~
      (__ \ "storeUrls").readNullable[Seq[String]].withDefault(AppDefinition.DefaultStoreUrls) ~
      (__ \ "requirePorts").readNullable[Boolean].withDefault(AppDefinition.DefaultRequirePorts) ~
      (__ \ "backoffSeconds").readNullable[Long].withDefault(AppDefinition.DefaultBackoff.toSeconds).asSeconds ~
      (__ \ "backoffFactor").readNullable[Double].withDefault(AppDefinition.DefaultBackoffFactor) ~
      (__ \ "maxLaunchDelaySeconds").readNullable[Long]
      .withDefault(AppDefinition.DefaultMaxLaunchDelay.toSeconds).asSeconds ~
      (__ \ "container").readNullable[Container] ~
      (__ \ "healthChecks").readNullable[Set[HealthCheck]].withDefault(AppDefinition.DefaultHealthChecks)
    ) ((
        id, cmd, args, maybeString, env, instances, cpus, mem, disk, executor, constraints, storeUrls,
        requirePorts, backoff, backoffFactor, maxLaunchDelay, container, checks
      ) => AppDefinition(
        id = id, cmd = cmd, args = args, user = maybeString, env = env, instances = instances, cpus = cpus,
        mem = mem, disk = disk, executor = executor, constraints = constraints, storeUrls = storeUrls,
        requirePorts = requirePorts, backoff = backoff,
        backoffFactor = backoffFactor, maxLaunchDelay = maxLaunchDelay, container = container,
        healthChecks = checks)).flatMap { app =>
        // necessary because of case class limitations (good for another 21 fields)
        case class ExtraFields(
            uris: Seq[String],
            fetch: Seq[FetchUri],
            dependencies: Set[PathId],
            maybePorts: Option[Seq[Int]],
            upgradeStrategy: Option[UpgradeStrategy],
            labels: Map[String, String],
            acceptedResourceRoles: Option[Set[String]],
            ipAddress: Option[IpAddress],
            version: Timestamp,
            residency: Option[Residency],
            maybePortDefinitions: Option[Seq[PortDefinition]],
            readinessChecks: Seq[ReadinessCheck],
            secrets: Map[String, Secret],
            maybeTaskKillGracePeriod: Option[FiniteDuration]) {
          def upgradeStrategyOrDefault: UpgradeStrategy = {
            import UpgradeStrategy.{ forResidentTasks, empty }
            upgradeStrategy.getOrElse {
              if (residencyOrDefault.isDefined || app.externalVolumes.nonEmpty) forResidentTasks else empty
            }
          }
          def residencyOrDefault: Option[Residency] = {
            residency.orElse(if (app.persistentVolumes.nonEmpty) Some(Residency.defaultResidency) else None)
          }
        }

        val extraReads: Reads[ExtraFields] =
          (
            (__ \ "uris").readNullable[Seq[String]].withDefault(AppDefinition.DefaultUris) ~
            (__ \ "fetch").readNullable[Seq[FetchUri]].withDefault(AppDefinition.DefaultFetch) ~
            (__ \ "dependencies").readNullable[Set[PathId]].withDefault(AppDefinition.DefaultDependencies) ~
            (__ \ "ports").readNullable[Seq[Int]](uniquePorts) ~
            (__ \ "upgradeStrategy").readNullable[UpgradeStrategy] ~
            (__ \ "labels").readNullable[Map[String, String]].withDefault(AppDefinition.Labels.Default) ~
            (__ \ "acceptedResourceRoles").readNullable[Set[String]](nonEmpty) ~
            (__ \ "ipAddress").readNullable[IpAddress] ~
            (__ \ "version").readNullable[Timestamp].withDefault(Timestamp.now()) ~
            (__ \ "residency").readNullable[Residency] ~
            (__ \ "portDefinitions").readNullable[Seq[PortDefinition]] ~
            (__ \ "readinessChecks").readNullable[Seq[ReadinessCheck]].withDefault(
              AppDefinition.DefaultReadinessChecks) ~
            (__ \ "secrets").readNullable[Map[String, Secret]].withDefault(AppDefinition.DefaultSecrets) ~
            (__ \ "taskKillGracePeriodSeconds").readNullable[Long].map(_.map(_.seconds))
          )(ExtraFields)
            .filter(ValidationError("You cannot specify both uris and fetch fields")) { extra =>
              !(extra.uris.nonEmpty && extra.fetch.nonEmpty)
            }
            .filter(ValidationError("You cannot specify both an IP address and ports")) { extra =>
              val appWithoutPorts = extra.maybePorts.forall(_.isEmpty) && extra.maybePortDefinitions.forall(_.isEmpty)
              appWithoutPorts || extra.ipAddress.isEmpty
            }
            .filter(ValidationError("You cannot specify both ports and port definitions")) { extra =>
              val portDefinitionsIsEquivalentToPorts = extra.maybePortDefinitions.map(_.map(_.port)) == extra.maybePorts
              portDefinitionsIsEquivalentToPorts || extra.maybePorts.isEmpty || extra.maybePortDefinitions.isEmpty
            }

        extraReads.map { extra =>
          def fetch: Seq[FetchUri] =
            if (extra.fetch.nonEmpty) extra.fetch
            else extra.uris.map { uri => FetchUri(uri = uri, extract = FetchUri.isExtract(uri)) }

          // Normally, our default is one port. If an ipAddress is defined that would lead to an error
          // if left unchanged.
          def portDefinitions: Seq[PortDefinition] = extra.ipAddress match {
            case Some(ipAddress) => Seq.empty[PortDefinition]
            case None =>
              extra.maybePortDefinitions.getOrElse {
                extra.maybePorts.map { ports =>
                  PortDefinitions.apply(ports: _*)
                }.getOrElse(AppDefinition.DefaultPortDefinitions)
              }
          }

          app.copy(
            fetch = fetch,
            dependencies = extra.dependencies,
            portDefinitions = portDefinitions,
            upgradeStrategy = extra.upgradeStrategyOrDefault,
            labels = extra.labels,
            acceptedResourceRoles = extra.acceptedResourceRoles,
            ipAddress = extra.ipAddress,
            versionInfo = AppDefinition.VersionInfo.OnlyVersion(extra.version),
            residency = extra.residencyOrDefault,
            readinessChecks = extra.readinessChecks,
            secrets = extra.secrets,
            taskKillGracePeriod = extra.maybeTaskKillGracePeriod
          )
        }
      }
  }.map(addHealthCheckPortIndexIfNecessary)

  /**
    * Ensure backwards compatibility by adding portIndex to health checks when necessary.
    *
    * In the past, healthCheck.portIndex was required and had a default value 0. When we introduced healthCheck.port, we
    * made it optional (also with ip-per-container in mind) and we have to re-add it in cases where it makes sense.
    */
  private[this] def addHealthCheckPortIndexIfNecessary(app: AppDefinition): AppDefinition = {
    val hasPortMappings = app.container.exists(_.docker.exists(_.portMappings.exists(_.nonEmpty)))
    val portIndexesMakeSense = app.portDefinitions.nonEmpty || hasPortMappings
    app.copy(healthChecks = app.healthChecks.map { healthCheck =>
      def needsDefaultPortIndex =
        healthCheck.port.isEmpty && healthCheck.portIndex.isEmpty && healthCheck.protocol != Protocol.COMMAND
      if (portIndexesMakeSense && needsDefaultPortIndex) healthCheck.copy(portIndex = Some(0))
      else healthCheck
    })
  }

  private[this] def addHealthCheckPortIndexIfNecessary(appUpdate: AppUpdate): AppUpdate = {
    appUpdate.copy(healthChecks = appUpdate.healthChecks.map { healthChecks =>
      healthChecks.map { healthCheck =>
        def needsDefaultPortIndex =
          healthCheck.port.isEmpty && healthCheck.portIndex.isEmpty && healthCheck.protocol != Protocol.COMMAND
        if (needsDefaultPortIndex) healthCheck.copy(portIndex = Some(0))
        else healthCheck
      }
    })
  }

  implicit lazy val taskLostBehaviorWrites = Writes[TaskLostBehavior] { taskLostBehavior =>
    JsString(taskLostBehavior.name())
  }

  implicit lazy val taskLostBehaviorReads = Reads[TaskLostBehavior] { json =>
    json.validate[String].flatMap { behaviorString: String =>

      Option(TaskLostBehavior.valueOf(behaviorString)) match {
        case Some(taskLostBehavior) => JsSuccess(taskLostBehavior)
        case None => {
          val allowedTaskLostBehaviorString =
            TaskLostBehavior.values().toSeq.map(_.getDescriptorForType.getName).mkString(", ")

          JsError(s"'$behaviorString' is not a valid taskLostBehavior. Allowed values: $allowedTaskLostBehaviorString")
        }
      }

    }
  }

  implicit lazy val ResidencyFormat: Format[Residency] = (
    (__ \ "relaunchEscalationTimeoutSeconds").formatNullable[Long]
    .withDefault(Residency.defaultRelaunchEscalationTimeoutSeconds) ~
    (__ \ "taskLostBehavior").formatNullable[TaskLostBehavior]
    .withDefault(Residency.defaultTaskLostBehaviour)
  ) (Residency(_, _), unlift(Residency.unapply))

  implicit lazy val RunSpecWrites: Writes[RunSpec] = {
    implicit lazy val durationWrites = Writes[FiniteDuration] { d =>
      JsNumber(d.toSeconds)
    }

    Writes[RunSpec] { runSpec =>
      var appJson: JsObject = Json.obj(
        "id" -> runSpec.id.toString,
        "cmd" -> runSpec.cmd,
        "args" -> runSpec.args,
        "user" -> runSpec.user,
        "env" -> runSpec.env,
        "instances" -> runSpec.instances,
        "cpus" -> runSpec.cpus,
        "mem" -> runSpec.mem,
        "disk" -> runSpec.disk,
        "executor" -> runSpec.executor,
        "constraints" -> runSpec.constraints,
        "uris" -> runSpec.fetch.map(_.uri),
        "fetch" -> runSpec.fetch,
        "storeUrls" -> runSpec.storeUrls,
        "backoffSeconds" -> runSpec.backoff,
        "backoffFactor" -> runSpec.backoffFactor,
        "maxLaunchDelaySeconds" -> runSpec.maxLaunchDelay,
        "container" -> runSpec.container,
        "healthChecks" -> runSpec.healthChecks,
        "readinessChecks" -> runSpec.readinessChecks,
        "dependencies" -> runSpec.dependencies,
        "upgradeStrategy" -> runSpec.upgradeStrategy,
        "labels" -> runSpec.labels,
        "acceptedResourceRoles" -> runSpec.acceptedResourceRoles,
        "ipAddress" -> runSpec.ipAddress,
        "version" -> runSpec.version,
        "residency" -> runSpec.residency,
        "secrets" -> runSpec.secrets,
        "taskKillGracePeriodSeconds" -> runSpec.taskKillGracePeriod
      )
      // top-level ports fields are incompatible with IP/CT
      if (runSpec.ipAddress.isEmpty) {
        appJson = appJson ++ Json.obj(
          "ports" -> runSpec.servicePorts,
          "portDefinitions" -> runSpec.portDefinitions.zip(runSpec.servicePorts).map {
            case (portDefinition, servicePort) => portDefinition.copy(port = servicePort)
          },
          // requirePorts only makes sense when allocating hostPorts, which you can't do in IP/CT mode
          "requirePorts" -> runSpec.requirePorts
        )
      }
      Json.toJson(runSpec.versionInfo) match {
        case JsNull => appJson
        case v: JsValue => appJson + ("versionInfo" -> v)
      }
    }
  }

  implicit lazy val VersionInfoWrites: Writes[AppDefinition.VersionInfo] =
    Writes[AppDefinition.VersionInfo] {
      case AppDefinition.VersionInfo.FullVersionInfo(_, lastScalingAt, lastConfigChangeAt) =>
        Json.obj(
          "lastScalingAt" -> lastScalingAt,
          "lastConfigChangeAt" -> lastConfigChangeAt
        )

      case AppDefinition.VersionInfo.OnlyVersion(version) => JsNull
      case AppDefinition.VersionInfo.NoVersion => JsNull
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

  implicit lazy val TaskStatsByVersionWrites: Writes[TaskStatsByVersion] =
    Writes { byVersion =>
      val maybeJsons = Seq[(String, Option[TaskStats])](
        "startedAfterLastScaling" -> byVersion.maybeStartedAfterLastScaling,
        "withLatestConfig" -> byVersion.maybeWithLatestConfig,
        "withOutdatedConfig" -> byVersion.maybeWithOutdatedConfig,
        "totalSummary" -> byVersion.maybeTotalSummary
      )
      Json.toJson(
        maybeJsons.iterator.flatMap {
        case (k, v) => v.map(k -> TaskStatsWrites.writes(_))
      }.toMap
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
        info.maybeGroups.map(groups => Json.obj("groups" -> groups))
      ).flatten

      val groupJson = Json.obj (
        "id" -> info.group.id,
        "dependencies" -> info.group.dependencies,
        "version" -> info.group.version
      )

      maybeJson.foldLeft(groupJson)((result, obj) => result ++ obj)
    }

  implicit lazy val AppUpdateReads: Reads[AppUpdate] = (
    (__ \ "id").readNullable[PathId].filterNot(_.exists(_.isRoot)) ~
    (__ \ "cmd").readNullable[String](Reads.minLength(1)) ~
    (__ \ "args").readNullable[Seq[String]] ~
    (__ \ "user").readNullable[String] ~
    (__ \ "env").readNullable[Map[String, EnvVarValue]] ~
    (__ \ "instances").readNullable[Int] ~
    (__ \ "cpus").readNullable[Double] ~
    (__ \ "mem").readNullable[Double] ~
    (__ \ "disk").readNullable[Double] ~
    (__ \ "executor").readNullable[String](Reads.pattern("^(//cmd)|(/?[^/]+(/[^/]+)*)|$".r)) ~
    (__ \ "constraints").readNullable[Set[Constraint]] ~
    (__ \ "storeUrls").readNullable[Seq[String]] ~
    (__ \ "requirePorts").readNullable[Boolean] ~
    (__ \ "backoffSeconds").readNullable[Long].map(_.map(_.seconds)) ~
    (__ \ "backoffFactor").readNullable[Double] ~
    (__ \ "maxLaunchDelaySeconds").readNullable[Long].map(_.map(_.seconds)) ~
    (__ \ "container").readNullable[Container] ~
    (__ \ "healthChecks").readNullable[Set[HealthCheck]] ~
    (__ \ "dependencies").readNullable[Set[PathId]]
  ) ((id, cmd, args, user, env, instances, cpus, mem, disk, executor, constraints, storeUrls, requirePorts,
      backoffSeconds, backoffFactor, maxLaunchDelaySeconds, container, healthChecks, dependencies) =>
      AppUpdate(
        id = id, cmd = cmd, args = args, user = user, env = env, instances = instances, cpus = cpus, mem = mem,
        disk = disk, executor = executor, constraints = constraints, storeUrls = storeUrls, requirePorts = requirePorts,
        backoff = backoffSeconds, backoffFactor = backoffFactor, maxLaunchDelay = maxLaunchDelaySeconds,
        container = container, healthChecks = healthChecks, dependencies = dependencies
      )
    ).flatMap { update =>
      // necessary because of case class limitations (good for another 21 fields)
      case class ExtraFields(
        uris: Option[Seq[String]],
        fetch: Option[Seq[FetchUri]],
        upgradeStrategy: Option[UpgradeStrategy],
        labels: Option[Map[String, String]],
        version: Option[Timestamp],
        acceptedResourceRoles: Option[Set[String]],
        ipAddress: Option[IpAddress],
        residency: Option[Residency],
        ports: Option[Seq[Int]],
        portDefinitions: Option[Seq[PortDefinition]],
        readinessChecks: Option[Seq[ReadinessCheck]],
        secrets: Option[Map[String, Secret]],
        taskKillGracePeriodSeconds: Option[FiniteDuration])

      val extraReads: Reads[ExtraFields] =
        (
          (__ \ "uris").readNullable[Seq[String]] ~
          (__ \ "fetch").readNullable[Seq[FetchUri]] ~
          (__ \ "upgradeStrategy").readNullable[UpgradeStrategy] ~
          (__ \ "labels").readNullable[Map[String, String]] ~
          (__ \ "version").readNullable[Timestamp] ~
          (__ \ "acceptedResourceRoles").readNullable[Set[String]](nonEmpty) ~
          (__ \ "ipAddress").readNullable[IpAddress] ~
          (__ \ "residency").readNullable[Residency] ~
          (__ \ "ports").readNullable[Seq[Int]](uniquePorts) ~
          (__ \ "portDefinitions").readNullable[Seq[PortDefinition]] ~
          (__ \ "readinessChecks").readNullable[Seq[ReadinessCheck]] ~
          (__ \ "secrets").readNullable[Map[String, Secret]] ~
          (__ \ "taskKillGracePeriodSeconds").readNullable[Long].map(_.map(_.seconds))
        )(ExtraFields)

      extraReads
        .filter(ValidationError("You cannot specify both uris and fetch fields")) { extra =>
          !(extra.uris.nonEmpty && extra.fetch.nonEmpty)
        }
        .filter(ValidationError("You cannot specify both ports and port definitions")) { extra =>
          val portDefinitionsIsEquivalentToPorts = extra.portDefinitions.map(_.map(_.port)) == extra.ports
          portDefinitionsIsEquivalentToPorts || extra.ports.isEmpty || extra.portDefinitions.isEmpty
        }
        .map { extra =>
          update.copy(
            upgradeStrategy = extra.upgradeStrategy,
            labels = extra.labels,
            version = extra.version,
            acceptedResourceRoles = extra.acceptedResourceRoles,
            ipAddress = extra.ipAddress,
            fetch = extra.fetch.orElse(extra.uris.map { seq => seq.map(FetchUri.apply(_)) }),
            residency = extra.residency,
            portDefinitions = extra.portDefinitions.orElse {
              extra.ports.map { ports => PortDefinitions.apply(ports: _*) }
            },
            readinessChecks = extra.readinessChecks,
            secrets = extra.secrets,
            taskKillGracePeriod = extra.taskKillGracePeriodSeconds
          )
        }
    }.map(addHealthCheckPortIndexIfNecessary)

  implicit lazy val GroupFormat: Format[Group] = (
    (__ \ "id").format[PathId] ~
    (__ \ "apps").formatNullable[Set[AppDefinition]].withDefault(Group.defaultApps) ~
    (__ \ "groups").lazyFormatNullable(implicitly[Format[Set[Group]]]).withDefault(Group.defaultGroups) ~
    (__ \ "dependencies").formatNullable[Set[PathId]].withDefault(Group.defaultDependencies) ~
    (__ \ "version").formatNullable[Timestamp].withDefault(Group.defaultVersion)
  ) (Group(_, _, _, _, _), unlift(Group.unapply))

  implicit lazy val PortDefinitionFormat: Format[PortDefinition] = (
    (__ \ "port").formatNullable[Int].withDefault(AppDefinition.RandomPortValue) ~
    (__ \ "protocol").formatNullable[String].withDefault("tcp") ~
    (__ \ "name").formatNullable[String] ~
    (__ \ "labels").formatNullable[Map[String, String]].withDefault(Map.empty[String, String])
  )(PortDefinition(_, _, _, _), unlift(PortDefinition.unapply))
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
