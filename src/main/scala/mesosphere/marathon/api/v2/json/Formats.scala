package mesosphere.marathon.api.v2.json

import java.lang.{ Double => JDouble }

import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.Protos.{ Constraint, MarathonTask }
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.event._
import mesosphere.marathon.event.http.EventSubscribers
import mesosphere.marathon.health.{ Health, HealthCheck }
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.Container.{ Docker, Volume }
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade._
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
import org.apache.mesos.{ Protos => mesos }
import play.api.data.validation.ValidationError
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.concurrent.duration._

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
    extends V2Formats
    with HealthCheckFormats
    with ContainerFormats
    with DeploymentFormats
    with EventFormats
    with EventSubscribersFormats {
  import scala.collection.JavaConverters._

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

  implicit lazy val MarathonTaskWrites: Writes[MarathonTask] = Writes { task =>
    Json.obj(
      "id" -> task.getId,
      "host" -> (if (task.hasHost) task.getHost else JsNull),
      "ports" -> task.getPortsList.asScala,
      "startedAt" -> (if (task.getStartedAt != 0) Timestamp(task.getStartedAt) else JsNull),
      "stagedAt" -> (if (task.getStagedAt != 0) Timestamp(task.getStagedAt) else JsNull),
      "version" -> task.getVersion,
      "slaveId" -> (if (task.hasSlaveId) task.getSlaveId.getValue else JsNull)
    )
  }

  implicit lazy val EnrichedTaskWrites: Writes[EnrichedTask] = Writes { task =>
    val taskJson = MarathonTaskWrites.writes(task.task).as[JsObject]

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

  implicit lazy val TimestampFormat: Format[Timestamp] = Format(
    Reads.of[String].map(Timestamp(_)),
    Writes[Timestamp] { t => JsString(t.toString) }
  )

  implicit lazy val IntegerFormat: Format[Integer] = Format(
    Reads.of[Int].map(Int.box),
    Writes[Integer] { i => JsNumber(i.intValue) }
  )
  implicit lazy val DoubleFormat: Format[JDouble] = Format(
    Reads.of[Double].map(Double.box),
    Writes[JDouble] { d => JsNumber(d.doubleValue()) }
  )

  implicit lazy val CommandFormat: Format[Command] = Json.format[Command]

  implicit lazy val ParameterFormat: Format[Parameter] = (
    (__ \ "key").format[String] ~
    (__ \ "value").format[String]
  )(Parameter(_, _), unlift(Parameter.unapply))

  /*
 * Helpers
 */

  def uniquePorts: Reads[Seq[Integer]] = Format.of[Seq[Integer]].filter { ports =>
    val withoutRandom = ports.filterNot(_ == AppDefinition.RandomPortValue)
    withoutRandom.distinct.size == withoutRandom.size
  }

  def nonEmpty[C <: Iterable[_]](implicit reads: Reads[C]): Reads[C] =
    Reads.filterNot[C](ValidationError(s"set must not be empty"))(_.isEmpty)(reads)

  def minValue[A](min: A)(implicit O: Ordering[A], reads: Reads[A]): Reads[A] =
    Reads.filterNot[A](ValidationError(s"value must not be less than $min"))(x => O.lt(x, min))(reads)

  def greaterThan[A](x: A)(implicit Ord: Ordering[A], reads: Reads[A]): Reads[A] =
    Reads.filter[A](ValidationError(s"value must be greater than $x"))(y => Ord.gt(y, x))(reads)

  def enumFormat[A <: java.lang.Enum[A]](read: String => A, errorMsg: String => String): Format[A] = {
    val reads = Reads[A] {
      case JsString(str) =>
        try {
          JsSuccess(read(str))
        }
        catch {
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

  implicit lazy val NetworkFormat: Format[Network] =
    enumFormat(Network.valueOf, str => s"$str is not a valid network type")

  implicit lazy val PortMappingFormat: Format[Docker.PortMapping] = (
    (__ \ "containerPort").formatNullable[Integer].withDefault(0) ~
    (__ \ "hostPort").formatNullable[Integer].withDefault(0) ~
    (__ \ "servicePort").formatNullable[Integer].withDefault(0) ~
    (__ \ "protocol").formatNullable[String].withDefault("tcp")
  )(PortMapping(_, _, _, _), unlift(PortMapping.unapply))

  implicit lazy val DockerFormat: Format[Docker] = (
    (__ \ "image").format[String] ~
    (__ \ "network").formatNullable[Network] ~
    (__ \ "portMappings").formatNullable[Seq[Docker.PortMapping]] ~
    (__ \ "privileged").formatNullable[Boolean].withDefault(false) ~
    (__ \ "parameters").formatNullable[Seq[Parameter]].withDefault(Seq.empty) ~
    (__ \ "forcePullImage").formatNullable[Boolean].withDefault(false)
  )(Docker(_, _, _, _, _, _), unlift(Docker.unapply))

  implicit lazy val ModeFormat: Format[mesos.Volume.Mode] =
    enumFormat(mesos.Volume.Mode.valueOf, str => s"$str is not a valid mode")

  implicit lazy val VolumeFormat: Format[Volume] = (
    (__ \ "containerPath").format[String] ~
    (__ \ "hostPath").format[String] ~
    (__ \ "mode").format[mesos.Volume.Mode]
  )(Volume(_, _, _), unlift(Volume.unapply))

  implicit lazy val ContainerTypeFormat: Format[mesos.ContainerInfo.Type] =
    enumFormat(mesos.ContainerInfo.Type.valueOf, str => s"$str is not a valid container type")

  implicit lazy val ContainerFormat: Format[Container] = (
    (__ \ "type").formatNullable[mesos.ContainerInfo.Type].withDefault(mesos.ContainerInfo.Type.DOCKER) ~
    (__ \ "volumes").formatNullable[Seq[Volume]].withDefault(Nil) ~
    (__ \ "docker").formatNullable[Docker]
  )(Container(_, _, _), unlift(Container.unapply))
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

  implicit lazy val V2GroupUpdateFormat: Format[V2GroupUpdate] = (
    (__ \ "id").formatNullable[PathId] ~
    (__ \ "apps").formatNullable[Set[V2AppDefinition]] ~
    (__ \ "groups").lazyFormatNullable(implicitly[Format[Set[V2GroupUpdate]]]) ~
    (__ \ "dependencies").formatNullable[Set[PathId]] ~
    (__ \ "scaleBy").formatNullable[Double] ~
    (__ \ "version").formatNullable[Timestamp]
  ) (V2GroupUpdate(_, _, _, _, _, _), unlift(V2GroupUpdate.unapply))

  implicit lazy val URLToStringMapFormat: Format[Map[java.net.URL, String]] = Format(
    Reads.of[Map[String, String]]
      .map(
        _.map { case (k, v) => new java.net.URL(k) -> v }
      ),
    Writes[Map[java.net.URL, String]] { m =>
      val mapped = m.map { case (k, v) => k.toString -> v }
      Json.toJson(m)
    }
  )

  implicit lazy val DeploymentActionWrites: Writes[DeploymentAction] = Writes { action =>
    Json.obj(
      "type" -> action.getClass.getSimpleName,
      "app" -> action.app.id
    )
  }

  implicit lazy val DeploymentStepWrites: Writes[DeploymentStep] = Json.writes[DeploymentStep]
}

trait EventFormats {
  import Formats._

  implicit lazy val AppTerminatedEventWrites: Writes[AppTerminatedEvent] = Json.writes[AppTerminatedEvent]

  implicit lazy val ApiPostEventWrites: Writes[ApiPostEvent] = Writes { event =>
    Json.obj(
      "clientIp" -> event.clientIp,
      "uri" -> event.uri,
      "appDefinition" -> V2AppDefinition(event.appDefinition),
      "eventType" -> event.eventType,
      "timestamp" -> event.timestamp
    )
  }

  implicit lazy val DeploymentPlanWrites: Writes[DeploymentPlan] = Writes { plan =>
    V2DeploymentPlanWrites.writes(V2DeploymentPlan(plan))
  }

  implicit lazy val SubscribeWrites: Writes[Subscribe] = Json.writes[Subscribe]
  implicit lazy val UnsubscribeWrites: Writes[Unsubscribe] = Json.writes[Unsubscribe]
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
    case event: AppTerminatedEvent         => Json.toJson(event)
    case event: ApiPostEvent               => Json.toJson(event)
    case event: Subscribe                  => Json.toJson(event)
    case event: Unsubscribe                => Json.toJson(event)
    case event: EventStreamAttached        => Json.toJson(event)
    case event: EventStreamDetached        => Json.toJson(event)
    case event: AddHealthCheck             => Json.toJson(event)
    case event: RemoveHealthCheck          => Json.toJson(event)
    case event: FailedHealthCheck          => Json.toJson(event)
    case event: HealthStatusChanged        => Json.toJson(event)
    case event: GroupChangeSuccess         => Json.toJson(event)
    case event: GroupChangeFailed          => Json.toJson(event)
    case event: DeploymentSuccess          => Json.toJson(event)
    case event: DeploymentFailed           => Json.toJson(event)
    case event: DeploymentStatus           => Json.toJson(event)
    case event: DeploymentStepSuccess      => Json.toJson(event)
    case event: DeploymentStepFailure      => Json.toJson(event)
    case event: MesosStatusUpdateEvent     => Json.toJson(event)
    case event: MesosFrameworkMessageEvent => Json.toJson(event)
    case event: SchedulerDisconnectedEvent => Json.toJson(event)
    case event: SchedulerRegisteredEvent   => Json.toJson(event)
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
      "taskId" -> health.taskId
    )
  }

  implicit lazy val ProtocolFormat: Format[Protocol] =
    enumFormat(Protocol.valueOf, str => s"$str is not a valid protocol")

  implicit lazy val HealthCheckFormat: Format[HealthCheck] = {
    import mesosphere.marathon.health.HealthCheck._

    (
      (__ \ "path").formatNullable[Option[String]].withDefault(DefaultPath) ~
      (__ \ "protocol").formatNullable[Protocol].withDefault(DefaultProtocol) ~
      (__ \ "portIndex").formatNullable[Integer].withDefault(DefaultPortIndex) ~
      (__ \ "command").formatNullable[Command] ~
      (__ \ "gracePeriodSeconds").formatNullable[Long].withDefault(DefaultGracePeriod.toSeconds).asSeconds ~
      (__ \ "intervalSeconds").formatNullable[Long].withDefault(DefaultInterval.toSeconds).asSeconds ~
      (__ \ "timeoutSeconds").formatNullable[Long].withDefault(DefaultTimeout.toSeconds).asSeconds ~
      (__ \ "maxConsecutiveFailures").formatNullable[Integer].withDefault(DefaultMaxConsecutiveFailures) ~
      (__ \ "ignoreHttp1xx").formatNullable[Boolean].withDefault(DefaultIgnoreHttp1xx)
    )(HealthCheck.apply, unlift(HealthCheck.unapply))
  }
}

trait V2Formats {
  import mesosphere.marathon.state.UpgradeStrategy._
  import Formats._

  implicit lazy val IdentifiableWrites = Json.writes[Identifiable]

  implicit lazy val UpgradeStrategyWrites: Writes[UpgradeStrategy] = {
    implicit lazy val durationWrites = Writes[FiniteDuration] { d =>
      JsNumber(d.toSeconds)
    }

    Writes[UpgradeStrategy] { upgradeStrategy =>
      Json.obj(
        "minimumHealthCapacity" -> upgradeStrategy.minimumHealthCapacity,
        "maximumOverCapacity" -> upgradeStrategy.maximumOverCapacity,
        "killOldTasksDelaySeconds" -> upgradeStrategy.killOldTasksDelay
      )
    }
  }

  implicit lazy val UpgradeStrategyReads: Reads[UpgradeStrategy] = {
    import mesosphere.marathon.state.AppDefinition._
    (
      (__ \ "minimumHealthCapacity").readNullable[JDouble].withDefault(DefaultUpgradeStrategy.minimumHealthCapacity) ~
      (__ \ "maximumOverCapacity").readNullable[JDouble].withDefault(DefaultUpgradeStrategy.maximumOverCapacity) ~
      (__ \ "killOldTasksDelaySeconds").readNullable[Long].withDefault(DefaultKillOldTasksDelay.toSeconds).asSeconds
    ) (UpgradeStrategy(_, _, _))
  }

  implicit lazy val ConstraintFormat: Format[Constraint] = Format(
    Reads.of[Seq[String]].map { seq =>
      val builder = Constraint
        .newBuilder()
        .setField(seq(0))
        .setOperator(Operator.valueOf(seq(1)))
      if (seq.size == 3) builder.setValue(seq(2))
      builder.build()
    },
    Writes[Constraint] { constraint =>
      val builder = Seq.newBuilder[JsString]
      builder += JsString(constraint.getField)
      builder += JsString(constraint.getOperator.name)
      if (constraint.hasValue) builder += JsString(constraint.getValue)
      JsArray(builder.result())
    }
  )

  implicit lazy val V2AppDefinitionReads: Reads[V2AppDefinition] = {
    val executorPattern = "^(//cmd)|(/?[^/]+(/[^/]+)*)|$".r

    (
      (__ \ "id").read[PathId].filterNot(_.isRoot) ~
      (__ \ "cmd").readNullable[String](Reads.minLength(1)) ~
      (__ \ "args").readNullable[Seq[String]] ~
      (__ \ "user").readNullable[String] ~
      (__ \ "env").readNullable[Map[String, String]].withDefault(AppDefinition.DefaultEnv) ~
      (__ \ "instances").readNullable[Integer](minValue(0)).withDefault(AppDefinition.DefaultInstances) ~
      (__ \ "cpus").readNullable[JDouble](greaterThan(0.0)).withDefault(AppDefinition.DefaultCpus) ~
      (__ \ "mem").readNullable[JDouble].withDefault(AppDefinition.DefaultMem) ~
      (__ \ "disk").readNullable[JDouble].withDefault(AppDefinition.DefaultDisk) ~
      (__ \ "executor").readNullable[String](Reads.pattern(executorPattern))
      .withDefault(AppDefinition.DefaultExecutor) ~
      (__ \ "constraints").readNullable[Set[Constraint]].withDefault(AppDefinition.DefaultConstraints) ~
      (__ \ "uris").readNullable[Seq[String]].withDefault(AppDefinition.DefaultUris) ~
      (__ \ "storeUrls").readNullable[Seq[String]].withDefault(AppDefinition.DefaultStoreUrls) ~
      (__ \ "ports").readNullable[Seq[Integer]](uniquePorts).withDefault(AppDefinition.DefaultPorts) ~
      (__ \ "requirePorts").readNullable[Boolean].withDefault(AppDefinition.DefaultRequirePorts) ~
      (__ \ "backoffSeconds").readNullable[Long].withDefault(AppDefinition.DefaultBackoff.toSeconds).asSeconds ~
      (__ \ "backoffFactor").readNullable[Double].withDefault(AppDefinition.DefaultBackoffFactor) ~
      (__ \ "maxLaunchDelaySeconds").readNullable[Long]
      .withDefault(AppDefinition.DefaultMaxLaunchDelay.toSeconds).asSeconds ~
      (__ \ "container").readNullable[Container] ~
      (__ \ "healthChecks").readNullable[Set[HealthCheck]].withDefault(AppDefinition.DefaultHealthChecks) ~
      (__ \ "dependencies").readNullable[Set[PathId]].withDefault(AppDefinition.DefaultDependencies)
    )(V2AppDefinition(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)).flatMap { app =>
        // necessary because of case class limitations (good for another 21 fields)
        case class ExtraFields(
          upgradeStrategy: UpgradeStrategy,
          labels: Map[String, String],
          acceptedResourceRoles: Option[Set[String]],
          version: Timestamp)

        val extraReads: Reads[ExtraFields] =
          (
            (__ \ "upgradeStrategy").readNullable[UpgradeStrategy].withDefault(AppDefinition.DefaultUpgradeStrategy) ~
            (__ \ "labels").readNullable[Map[String, String]].withDefault(AppDefinition.DefaultLabels) ~
            (__ \ "acceptedResourceRoles").readNullable[Set[String]](nonEmpty) ~
            (__ \ "version").readNullable[Timestamp].withDefault(Timestamp.now())
          )(ExtraFields)

        extraReads.map { extraFields =>
          app.copy(
            upgradeStrategy = extraFields.upgradeStrategy,
            labels = extraFields.labels,
            acceptedResourceRoles = extraFields.acceptedResourceRoles,
            version = extraFields.version,
            versionInfo = None
          )
        }
      }
  }

  implicit lazy val V2AppDefinitionWrites: Writes[V2AppDefinition] = {
    implicit lazy val durationWrites = Writes[FiniteDuration] { d =>
      JsNumber(d.toSeconds)
    }

    Writes[V2AppDefinition] { app =>
      val appJson: JsObject = Json.obj(
        "id" -> app.id.toString,
        "cmd" -> app.cmd,
        "args" -> app.args,
        "user" -> app.user,
        "env" -> app.env,
        "instances" -> app.instances,
        "cpus" -> app.cpus,
        "mem" -> app.mem,
        "disk" -> app.disk,
        "executor" -> app.executor,
        "constraints" -> app.constraints,
        "uris" -> app.uris,
        "storeUrls" -> app.storeUrls,
        // the ports field was written incorrectly in old code if a container was specified
        // it should contain the service ports
        "ports" -> app.toAppDefinition.servicePorts,
        "requirePorts" -> app.requirePorts,
        "backoffSeconds" -> app.backoff,
        "backoffFactor" -> app.backoffFactor,
        "maxLaunchDelaySeconds" -> app.maxLaunchDelay,
        "container" -> app.container,
        "healthChecks" -> app.healthChecks,
        "dependencies" -> app.dependencies,
        "upgradeStrategy" -> app.upgradeStrategy,
        "labels" -> app.labels,
        "acceptedResourceRoles" -> app.acceptedResourceRoles,
        "version" -> app.version
      )

      app.versionInfo.fold(appJson)(versionInfo => appJson + ("versionInfo" -> Json.toJson(versionInfo)))
    }
  }

  implicit lazy val VersionInfoWrites: Writes[V2AppDefinition.VersionInfo] =
    Writes {
      case V2AppDefinition.VersionInfo(lastScalingAt, lastConfigChangeAt) =>
        Json.obj(
          "lastScalingAt" -> lastScalingAt,
          "lastConfigChangeAt" -> lastConfigChangeAt
        )
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
      val appJson = V2AppDefinitionWrites.writes(V2AppDefinition(info.app)).as[JsObject]

      val maybeJson = Seq[Option[JsObject]](
        info.maybeCounts.map(TaskCountsWrites.writes(_).as[JsObject]),
        info.maybeDeployments.map(deployments => Json.obj("deployments" -> deployments)),
        info.maybeTasks.map(tasks => Json.obj("tasks" -> tasks)),
        info.maybeLastTaskFailure.map(lastFailure => Json.obj("lastTaskFailure" -> lastFailure)),
        info.maybeTaskStats.map(taskStats => Json.obj("taskStats" -> taskStats))
      ).flatten

      maybeJson.foldLeft(appJson)((result, obj) => result ++ obj)
    }

  implicit lazy val V2AppUpdateReads: Reads[V2AppUpdate] = {

    (
      (__ \ "id").readNullable[PathId].filterNot(_.exists(_.isRoot)) ~
      (__ \ "cmd").readNullable[String](Reads.minLength(1)) ~
      (__ \ "args").readNullable[Seq[String]] ~
      (__ \ "user").readNullable[String] ~
      (__ \ "env").readNullable[Map[String, String]] ~
      (__ \ "instances").readNullable[Integer](minValue(0)) ~
      (__ \ "cpus").readNullable[JDouble](greaterThan(0.0)) ~
      (__ \ "mem").readNullable[JDouble] ~
      (__ \ "disk").readNullable[JDouble] ~
      (__ \ "executor").readNullable[String](Reads.pattern("^(//cmd)|(/?[^/]+(/[^/]+)*)|$".r)) ~
      (__ \ "constraints").readNullable[Set[Constraint]] ~
      (__ \ "uris").readNullable[Seq[String]] ~
      (__ \ "storeUrls").readNullable[Seq[String]] ~
      (__ \ "ports").readNullable[Seq[Integer]](uniquePorts) ~
      (__ \ "requirePorts").readNullable[Boolean] ~
      (__ \ "backoffSeconds").readNullable[Long].map(_.map(_.seconds)) ~
      (__ \ "backoffFactor").readNullable[JDouble] ~
      (__ \ "maxLaunchDelaySeconds").readNullable[Long].map(_.map(_.seconds)) ~
      (__ \ "container").readNullable[Container] ~
      (__ \ "healthChecks").readNullable[Set[HealthCheck]] ~
      (__ \ "dependencies").readNullable[Set[PathId]]
    )(V2AppUpdate(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)).flatMap { update =>
        // necessary because of case class limitations (good for another 21 fields)
        case class ExtraFields(
          upgradeStrategy: Option[UpgradeStrategy],
          labels: Option[Map[String, String]],
          version: Option[Timestamp],
          acceptedResourceRoles: Option[Set[String]])

        val extraReads: Reads[ExtraFields] =
          (
            (__ \ "upgradeStrategy").readNullable[UpgradeStrategy] ~
            (__ \ "labels").readNullable[Map[String, String]] ~
            (__ \ "version").readNullable[Timestamp] ~
            (__ \ "acceptedResourceRoles").readNullable[Set[String]](nonEmpty)
          )(ExtraFields)

        extraReads.map { extraFields =>
          update.copy(
            upgradeStrategy = extraFields.upgradeStrategy,
            labels = extraFields.labels,
            version = extraFields.version,
            acceptedResourceRoles = extraFields.acceptedResourceRoles
          )
        }

      }
  }

  implicit lazy val V2GroupFormat: Format[V2Group] = (
    (__ \ "id").format[PathId] ~
    (__ \ "apps").formatNullable[Set[V2AppDefinition]].withDefault(V2Group.defaultApps) ~
    (__ \ "groups").lazyFormatNullable(implicitly[Format[Set[V2Group]]]).withDefault(V2Group.defaultGroups) ~
    (__ \ "dependencies").formatNullable[Set[PathId]].withDefault(Group.defaultDependencies) ~
    (__ \ "version").formatNullable[Timestamp].withDefault(Group.defaultVersion)
  )(V2Group(_, _, _, _, _), unlift(V2Group.unapply))

  implicit lazy val V2DeploymentPlanWrites: Writes[V2DeploymentPlan] = Writes { plan =>
    Json.obj(
      "id" -> plan.id,
      "original" -> plan.original,
      "target" -> plan.target,
      "steps" -> plan.steps,
      "version" -> plan.version
    )
  }
}

