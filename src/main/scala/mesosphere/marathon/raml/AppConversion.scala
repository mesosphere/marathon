package mesosphere.marathon
package raml

import java.time.OffsetDateTime

import mesosphere.marathon.state.{ AppDefinition, FetchUri, PathId, Residency }

trait AppConversion extends ConstraintConversion with EnvVarConversion with SecretConversion with NetworkConversion
    with ReadinessConversions with HealthCheckConversion with UnreachableStrategyConversion with KillSelectionConversion {

  implicit val pathIdWrites: Writes[PathId, String] = Writes { _.toString }

  implicit val artifactWrites: Writes[FetchUri, Artifact] = Writes { fetch =>
    Artifact(fetch.uri, Some(fetch.extract), Some(fetch.executable), Some(fetch.cache))
  }

  implicit val upgradeStrategyWrites: Writes[state.UpgradeStrategy, UpgradeStrategy] = Writes { strategy =>
    UpgradeStrategy(strategy.maximumOverCapacity, strategy.minimumHealthCapacity)
  }

  implicit val appResidencyWrites: Writes[Residency, AppResidency] = Writes { residency =>
    AppResidency(residency.relaunchEscalationTimeoutSeconds.toInt, residency.taskLostBehavior.toRaml)
  }

  implicit val versionInfoWrites: Writes[state.VersionInfo, VersionInfo] = Writes {
    case state.VersionInfo.FullVersionInfo(_, scale, config) => VersionInfo(scale.toOffsetDateTime, config.toOffsetDateTime)
    case state.VersionInfo.OnlyVersion(version) => VersionInfo(version.toOffsetDateTime, version.toOffsetDateTime)
    case state.VersionInfo.NoVersion => VersionInfo(OffsetDateTime.now(), OffsetDateTime.now())
  }

  implicit val parameterWrites: Writes[state.Parameter, DockerParameter] = Writes { param =>
    DockerParameter(param.key, param.value)
  }

  implicit val appWriter: Writes[AppDefinition, App] = Writes { app =>
    App(
      id = app.id.toString,
      acceptedResourceRoles = if (app.acceptedResourceRoles.nonEmpty) Some(app.acceptedResourceRoles) else None,
      args = app.args,
      backoffFactor = app.backoffStrategy.factor,
      backoffSeconds = app.backoffStrategy.backoff.toSeconds.toInt,
      cmd = app.cmd,
      constraints = app.constraints.toRaml[Set[Seq[String]]],
      container = app.container.toRaml,
      cpus = app.resources.cpus,
      dependencies = app.dependencies.toRaml,
      disk = app.resources.disk,
      env = app.env.toRaml,
      executor = app.executor,
      fetch = app.fetch.toRaml,
      healthChecks = app.healthChecks.toRaml,
      instances = app.instances,
      labels = app.labels,
      maxLaunchDelaySeconds = app.backoffStrategy.maxLaunchDelay.toSeconds.toInt,
      mem = app.resources.mem,
      gpus = app.resources.gpus,
      ipAddress = app.ipAddress.toRaml,
      ports = None, // deprecated field
      portDefinitions = if (app.portDefinitions.nonEmpty) Some(app.portDefinitions.toRaml) else None,
      readinessChecks = app.readinessChecks.toRaml,
      residency = app.residency.toRaml,
      requirePorts = Some(app.requirePorts),
      secrets = app.secrets.toRaml,
      storeUrls = app.storeUrls,
      taskKillGracePeriodSeconds = app.taskKillGracePeriod.map(_.toSeconds.toInt),
      upgradeStrategy = Some(app.upgradeStrategy.toRaml),
      uris = None, // deprecated field
      user = app.user,
      version = Some(app.versionInfo.version.toOffsetDateTime),
      versionInfo = Some(app.versionInfo.toRaml),
      unreachableStrategy = Some(app.unreachableStrategy.toRaml),
      killSelection = Some(app.killSelection.toRaml)
    )
  }
}
