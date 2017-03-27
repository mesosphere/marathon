package mesosphere.marathon
package raml

import mesosphere.marathon.Protos.ResidencyDefinition
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.Implicits._

import scala.concurrent.duration._

trait AppConversion extends ConstraintConversion with EnvVarConversion with HealthCheckConversion
    with NetworkConversion with ReadinessConversions with SecretConversion with VolumeConversion with UnreachableStrategyConversion with KillSelectionConversion {

  import AppConversion._

  implicit val pathIdWrites: Writes[PathId, String] = Writes { _.toString }

  implicit val artifactWrites: Writes[FetchUri, Artifact] = Writes { fetch =>
    Artifact(fetch.uri, fetch.extract, fetch.executable, fetch.cache)
  }

  implicit val upgradeStrategyWrites: Writes[state.UpgradeStrategy, UpgradeStrategy] = Writes { strategy =>
    UpgradeStrategy(strategy.maximumOverCapacity, strategy.minimumHealthCapacity)
  }

  implicit val appResidencyWrites: Writes[Residency, AppResidency] = Writes { residency =>
    AppResidency(residency.relaunchEscalationTimeoutSeconds, residency.taskLostBehavior.toRaml)
  }

  implicit val versionInfoWrites: Writes[state.VersionInfo, Option[VersionInfo]] = Writes {
    case state.VersionInfo.FullVersionInfo(_, scale, config) => Some(VersionInfo(scale.toOffsetDateTime, config.toOffsetDateTime))
    case state.VersionInfo.OnlyVersion(_) => None
    case state.VersionInfo.NoVersion => None
  }

  implicit val appWriter: Writes[AppDefinition, App] = Writes { app =>
    // we explicitly do not write ports, uris, ipAddress because they are deprecated fields
    App(
      id = app.id.toString,
      acceptedResourceRoles = if (app.acceptedResourceRoles.nonEmpty) Some(app.acceptedResourceRoles) else None,
      args = app.args,
      backoffFactor = app.backoffStrategy.factor,
      backoffSeconds = app.backoffStrategy.backoff.toSeconds.toInt,
      cmd = app.cmd,
      constraints = app.constraints.toRaml[Set[Seq[String]]],
      container = app.container.toRaml.map { container =>
        // change container.portMappings to None (vs an empty collection) depending on network mode
        if (app.networks.hasNonHostNetworking) container
        else container.copy(portMappings = None)
      },
      cpus = app.resources.cpus,
      dependencies = app.dependencies.map(Raml.toRaml(_)),
      disk = app.resources.disk,
      env = app.env.toRaml,
      executor = app.executor,
      fetch = app.fetch.toRaml,
      gpus = app.resources.gpus,
      healthChecks = app.healthChecks.toRaml,
      instances = app.instances,
      ipAddress = None, // deprecated field
      labels = app.labels,
      maxLaunchDelaySeconds = app.backoffStrategy.maxLaunchDelay.toSeconds.toInt,
      mem = app.resources.mem,
      networks = app.networks.toRaml,
      ports = None, // deprecated field
      portDefinitions = if (app.networks.hasNonHostNetworking) None else Some(app.portDefinitions.toRaml),
      readinessChecks = app.readinessChecks.toRaml,
      residency = app.residency.toRaml,
      requirePorts = app.requirePorts,
      secrets = app.secrets.toRaml,
      storeUrls = app.storeUrls,
      taskKillGracePeriodSeconds = app.taskKillGracePeriod.map(_.toSeconds.toInt),
      upgradeStrategy = Some(app.upgradeStrategy.toRaml),
      uris = None, // deprecated field
      user = app.user,
      version = Some(app.versionInfo.version.toOffsetDateTime),
      versionInfo = app.versionInfo.toRaml,
      unreachableStrategy = Some(app.unreachableStrategy.toRaml),
      killSelection = app.killSelection.toRaml
    )
  }

  def resources(cpus: Option[Double], mem: Option[Double], disk: Option[Double], gpus: Option[Int]): Resources =
    Resources(
      cpus = cpus.getOrElse(App.DefaultCpus),
      mem = mem.getOrElse(App.DefaultMem),
      disk = disk.getOrElse(App.DefaultDisk),
      gpus = gpus.getOrElse(App.DefaultGpus)
    )

  implicit val taskLostBehaviorReader: Reads[TaskLostBehavior, ResidencyDefinition.TaskLostBehavior] = Reads { taskLost =>
    import ResidencyDefinition.TaskLostBehavior._
    taskLost match {
      case TaskLostBehavior.RelaunchAfterTimeout => RELAUNCH_AFTER_TIMEOUT
      case TaskLostBehavior.WaitForever => WAIT_FOREVER
    }
  }

  implicit val residencyRamlReader: Reads[AppResidency, Residency] = Reads { residency =>
    Residency(
      relaunchEscalationTimeoutSeconds = residency.relaunchEscalationTimeoutSeconds,
      taskLostBehavior = residency.taskLostBehavior.fromRaml
    )
  }

  implicit val fetchUriReader: Reads[Artifact, FetchUri] = Reads { artifact =>
    FetchUri(
      uri = artifact.uri,
      extract = artifact.extract,
      executable = artifact.executable,
      cache = artifact.cache,
      outputFile = artifact.destPath
    )
  }

  implicit val upgradeStrategyRamlReader: Reads[UpgradeStrategy, state.UpgradeStrategy] = Reads { us =>
    state.UpgradeStrategy(
      maximumOverCapacity = us.maximumOverCapacity,
      minimumHealthCapacity = us.minimumHealthCapacity
    )
  }

  /**
    * Generate an AppDefinition from an App RAML. Note: App.versionInfo is ignored, the resulting AppDefinition
    * has a `versionInfo` constructed from `OnlyVersion(app.version)`.
    */
  implicit val appRamlReader: Reads[App, AppDefinition] = Reads[App, AppDefinition] { app =>
    val selectedStrategy = ResidencyAndUpgradeStrategy(
      app.residency.map(Raml.fromRaml(_)),
      app.upgradeStrategy.map(Raml.fromRaml(_)),
      app.container.exists(_.volumes.exists(_.persistent.nonEmpty)),
      app.container.exists(_.volumes.exists(_.external.nonEmpty))
    )

    val backoffStrategy = BackoffStrategy(
      backoff = app.backoffSeconds.seconds,
      maxLaunchDelay = app.maxLaunchDelaySeconds.seconds,
      factor = app.backoffFactor
    )

    val versionInfo = state.VersionInfo.OnlyVersion(app.version.map(Timestamp(_)).getOrElse(Timestamp.now()))

    val result: AppDefinition = AppDefinition(
      id = PathId(app.id),
      cmd = app.cmd,
      args = app.args,
      user = app.user,
      env = Raml.fromRaml(app.env),
      instances = app.instances,
      resources = resources(Some(app.cpus), Some(app.mem), Some(app.disk), Some(app.gpus)),
      executor = app.executor,
      constraints = app.constraints.map(Raml.fromRaml(_))(collection.breakOut),
      fetch = app.fetch.map(Raml.fromRaml(_)),
      storeUrls = app.storeUrls,
      portDefinitions = app.portDefinitions.map(_.map(Raml.fromRaml(_))).getOrElse(Nil),
      requirePorts = app.requirePorts,
      backoffStrategy = backoffStrategy,
      container = app.container.map(Raml.fromRaml(_)),
      healthChecks = app.healthChecks.map(Raml.fromRaml(_)),
      readinessChecks = app.readinessChecks.map(Raml.fromRaml(_)),
      taskKillGracePeriod = app.taskKillGracePeriodSeconds.map(_.second),
      dependencies = app.dependencies.map(PathId(_))(collection.breakOut),
      upgradeStrategy = selectedStrategy.upgradeStrategy,
      labels = app.labels,
      acceptedResourceRoles = app.acceptedResourceRoles.getOrElse(AppDefinition.DefaultAcceptedResourceRoles),
      networks = app.networks.map(Raml.fromRaml(_)),
      versionInfo = versionInfo,
      residency = selectedStrategy.residency,
      secrets = Raml.fromRaml(app.secrets),
      unreachableStrategy = app.unreachableStrategy.map(_.fromRaml).getOrElse(AppDefinition.DefaultUnreachableStrategy),
      killSelection = app.killSelection.fromRaml
    )
    result
  }

  implicit val appUpdateRamlReader: Reads[(AppUpdate, AppDefinition), App] = Reads { src =>
    val (update: AppUpdate, appDef: AppDefinition) = src
    // for validating and converting the returned App API object
    val app: App = appDef.toRaml
    app.copy(
      // id stays the same
      cmd = update.cmd.orElse(app.cmd),
      args = update.args.getOrElse(app.args),
      user = update.user.orElse(app.user),
      env = update.env.getOrElse(app.env),
      instances = update.instances.getOrElse(app.instances),
      cpus = update.cpus.getOrElse(app.cpus),
      mem = update.mem.getOrElse(app.mem),
      disk = update.disk.getOrElse(app.disk),
      gpus = update.gpus.getOrElse(app.gpus),
      executor = update.executor.getOrElse(app.executor),
      constraints = update.constraints.getOrElse(app.constraints),
      fetch = update.fetch.getOrElse(app.fetch),
      storeUrls = update.storeUrls.getOrElse(app.storeUrls),
      portDefinitions = update.portDefinitions.orElse(app.portDefinitions),
      requirePorts = update.requirePorts.getOrElse(app.requirePorts),
      backoffFactor = update.backoffFactor.getOrElse(app.backoffFactor),
      backoffSeconds = update.backoffSeconds.getOrElse(app.backoffSeconds),
      maxLaunchDelaySeconds = update.maxLaunchDelaySeconds.getOrElse(app.maxLaunchDelaySeconds),
      container = update.container.orElse(app.container),
      healthChecks = update.healthChecks.getOrElse(app.healthChecks),
      readinessChecks = update.readinessChecks.getOrElse(app.readinessChecks),
      dependencies = update.dependencies.getOrElse(app.dependencies),
      upgradeStrategy = update.upgradeStrategy.orElse(app.upgradeStrategy),
      labels = update.labels.getOrElse(app.labels),
      acceptedResourceRoles = update.acceptedResourceRoles.orElse(app.acceptedResourceRoles),
      networks = update.networks.getOrElse(app.networks),
      // versionInfo doesn't change - it's never overridden by an AppUpdate.
      // Setting the version in AppUpdate means that the user wants to revert to that version. In that
      // case, we do not update the current AppDefinition but revert completely to the specified version.
      // For all other updates, the GroupVersioningUtil will determine a new version if the AppDefinition
      // has really changed.
      // Since we return an App, and conversion from App to AppDefinition loses versionInfo, we don't take
      // any special steps here to preserve it; that's the caller's responsibility.
      residency = update.residency.orElse(app.residency),
      secrets = update.secrets.getOrElse(app.secrets),
      taskKillGracePeriodSeconds = update.taskKillGracePeriodSeconds.orElse(app.taskKillGracePeriodSeconds),
      unreachableStrategy = update.unreachableStrategy.orElse(app.unreachableStrategy),
      killSelection = update.killSelection.getOrElse(app.killSelection)
    )
  }

  //
  // protobuf to RAML conversions, useful for migration
  //

  implicit val artifactProtoRamlWriter: Writes[org.apache.mesos.Protos.CommandInfo.URI, Artifact] = Writes { uri =>
    Artifact(
      uri = uri.getValue,
      extract = uri.whenOrElse(_.hasExtract, _.getExtract, Artifact.DefaultExtract),
      executable = uri.whenOrElse(_.hasExecutable, _.getExecutable, Artifact.DefaultExecutable),
      cache = uri.whenOrElse(_.hasCache, _.getCache, Artifact.DefaultCache),
      destPath = uri.when(_.hasOutputFile, _.getOutputFile).orElse(Artifact.DefaultDestPath)
    )
  }

  implicit val portDefinitionProtoRamlWriter: Writes[org.apache.mesos.Protos.Port, PortDefinition] = Writes { port =>
    PortDefinition(
      port = port.whenOrElse(_.hasNumber, _.getNumber, PortDefinition.DefaultPort),
      labels = port.getLabels.fromProto,
      name = port.when(_.hasName, _.getName).orElse(PortDefinition.DefaultName),
      protocol = port.when(_.hasProtocol, _.getProtocol).flatMap(NetworkProtocol.fromString).getOrElse(PortDefinition.DefaultProtocol)
    )
  }

  implicit val upgradeStrategyProtoRamlWriter: Writes[Protos.UpgradeStrategyDefinition, UpgradeStrategy] = Writes { upgrade =>
    UpgradeStrategy(
      maximumOverCapacity = upgrade.getMaximumOverCapacity,
      minimumHealthCapacity = upgrade.getMinimumHealthCapacity
    )
  }

  implicit val taskLostProtoRamlWriter: Writes[Protos.ResidencyDefinition.TaskLostBehavior, TaskLostBehavior] = Writes { lost =>
    import Protos.ResidencyDefinition.TaskLostBehavior._
    lost match {
      case WAIT_FOREVER => TaskLostBehavior.WaitForever
      case RELAUNCH_AFTER_TIMEOUT => TaskLostBehavior.RelaunchAfterTimeout
      case badBehavior => throw new IllegalStateException(s"unsupported value for task lost behavior $badBehavior")
    }
  }

  implicit val residencyProtoRamlWriter: Writes[Protos.ResidencyDefinition, AppResidency] = Writes { res =>
    AppResidency(
      relaunchEscalationTimeoutSeconds = res.whenOrElse(
        _.hasRelaunchEscalationTimeoutSeconds, _.getRelaunchEscalationTimeoutSeconds, AppResidency.DefaultRelaunchEscalationTimeoutSeconds),
      taskLostBehavior = res.whenOrElse(_.hasTaskLostBehavior, _.getTaskLostBehavior.toRaml, AppResidency.DefaultTaskLostBehavior)
    )
  }

  implicit val discoveryPortProtoRamlWriter: Writes[org.apache.mesos.Protos.Port, IpDiscoveryPort] = Writes { port =>
    IpDiscoveryPort(
      number = port.whenOrElse(_.hasNumber, _.getNumber, IpDiscoveryPort.DefaultNumber),
      name = port.getName,
      protocol = port.when(_.hasProtocol, _.getProtocol).flatMap(NetworkProtocol.fromString).getOrElse(IpDiscoveryPort.DefaultProtocol)
    )
  }

  implicit val discoveryProtoRamlWriter: Writes[Protos.ObsoleteDiscoveryInfo, IpDiscovery] = Writes { di =>
    IpDiscovery(
      ports = di.whenOrElse(_.getPortsCount > 0, _.getPortsList.map(_.toRaml[IpDiscoveryPort])(collection.breakOut), IpDiscovery.DefaultPorts)
    )
  }

  implicit val ipAddressProtoRamlWriter: Writes[Protos.ObsoleteIpAddress, IpAddress] = Writes { ip =>
    IpAddress(
      discovery = ip.collect {
        case x if x.hasDiscoveryInfo && x.getDiscoveryInfo.getPortsCount > 0 => x.getDiscoveryInfo.toRaml
      }.orElse(IpAddress.DefaultDiscovery),
      groups = ip.whenOrElse(_.getGroupsCount > 0, _.getGroupsList.to[Set], IpAddress.DefaultGroups),
      labels = ip.whenOrElse(_.getLabelsCount > 0, _.getLabelsList.to[Seq].fromProto, IpAddress.DefaultLabels),
      networkName = ip.when(_.hasNetworkName, _.getNetworkName).orElse(IpAddress.DefaultNetworkName)
    )
  }

  implicit val appProtoRamlWriter: Writes[Protos.ServiceDefinition, App] = Writes { service =>
    import mesosphere.mesos.protos.Resource

    val resourcesMap: Map[String, Double] =
      service.getResourcesList.map {
        r => r.getName -> (r.getScalar.getValue: Double)
      }(collection.breakOut)

    val version = service.when(_.hasVersion, s => Timestamp(s.getVersion).toOffsetDateTime).orElse(App.DefaultVersion)
    val versionInfo: Option[VersionInfo] =
      if (service.hasLastScalingAt) Option(VersionInfo(
        lastConfigChangeAt = Timestamp(service.getLastConfigChangeAt).toOffsetDateTime,
        lastScalingAt = Timestamp(service.getLastScalingAt).toOffsetDateTime
      ))
      else None

    val app = App(
      id = service.getId,
      acceptedResourceRoles = if (service.hasAcceptedResourceRoles && service.getAcceptedResourceRoles.getRoleCount > 0) Option(service.getAcceptedResourceRoles.getRoleList.to[Set]) else App.DefaultAcceptedResourceRoles,
      args = if (service.hasCmd && service.getCmd.getArgumentsCount > 0) service.getCmd.getArgumentsList.to[Seq] else App.DefaultArgs,
      backoffFactor = service.whenOrElse(_.hasBackoffFactor, _.getBackoffFactor, App.DefaultBackoffFactor),
      backoffSeconds = service.whenOrElse(_.hasBackoff, b => (b.getBackoff / 1000L).toInt, App.DefaultBackoffSeconds),
      cmd = if (service.hasCmd && service.getCmd.hasValue) Option(service.getCmd.getValue) else App.DefaultCmd,
      constraints = service.whenOrElse(_.getConstraintsCount > 0, _.getConstraintsList.map(_.toRaml[Seq[String]])(collection.breakOut), App.DefaultConstraints),
      container = service.when(_.hasContainer, _.getContainer.toRaml).orElse(App.DefaultContainer),
      cpus = resourcesMap.getOrElse(Resource.CPUS, App.DefaultCpus),
      dependencies = service.whenOrElse(_.getDependenciesCount > 0, _.getDependenciesList.to[Set], App.DefaultDependencies),
      disk = resourcesMap.getOrElse(Resource.DISK, App.DefaultDisk),
      env = service.whenOrElse(_.hasCmd, s => (s.getCmd.getEnvironment.getVariablesList.to[Seq], s.getEnvVarReferencesList.to[Seq]).toRaml, App.DefaultEnv),
      executor = service.whenOrElse(_.hasExecutor, _.getExecutor, App.DefaultExecutor),
      fetch = if (service.hasCmd && service.getCmd.getUrisCount > 0) service.getCmd.getUrisList.toRaml else App.DefaultFetch,
      healthChecks = service.whenOrElse(_.getHealthChecksCount > 0, _.getHealthChecksList.toRaml.to[Set], App.DefaultHealthChecks),
      instances = service.whenOrElse(_.hasInstances, _.getInstances, App.DefaultInstances),
      labels = service.getLabelsList.map { label => label.getKey -> label.getValue }(collection.breakOut),
      maxLaunchDelaySeconds = service.whenOrElse(_.hasMaxLaunchDelay, m => (m.getMaxLaunchDelay / 1000L).toInt, App.DefaultMaxLaunchDelaySeconds),
      mem = resourcesMap.getOrElse(Resource.MEM, App.DefaultMem),
      gpus = resourcesMap.get(Resource.GPUS).fold(App.DefaultGpus)(_.toInt),
      ipAddress = service.when(_.hasOBSOLETEIpAddress, _.getOBSOLETEIpAddress.toRaml).orElse(App.DefaultIpAddress),
      networks = service.whenOrElse(_.getNetworksCount > 0, _.getNetworksList.toRaml, App.DefaultNetworks),
      ports = None, // not stored in protobuf
      portDefinitions = Option(Seq.empty[PortDefinition]).unless( // the RAML default is None, which is not what an empty proto port collection means.
        service.when(_.getPortDefinitionsCount > 0, _.getPortDefinitionsList.map(_.toRaml[PortDefinition])(collection.breakOut))),
      readinessChecks = service.whenOrElse(_.getReadinessCheckDefinitionCount > 0, _.getReadinessCheckDefinitionList.toRaml, App.DefaultReadinessChecks),
      residency = service.when(_.hasResidency, _.getResidency.toRaml).orElse(App.DefaultResidency),
      requirePorts = service.whenOrElse(_.hasRequirePorts, _.getRequirePorts, App.DefaultRequirePorts),
      secrets = service.whenOrElse(_.getSecretsCount > 0, _.getSecretsList.map(_.toRaml)(collection.breakOut), App.DefaultSecrets),
      storeUrls = service.whenOrElse(_.getStoreUrlsCount > 0, _.getStoreUrlsList.to[Seq], App.DefaultStoreUrls),
      taskKillGracePeriodSeconds = service.when(_.hasTaskKillGracePeriod, _.getTaskKillGracePeriod.toInt).orElse(App.DefaultTaskKillGracePeriodSeconds),
      upgradeStrategy = service.when(_.hasUpgradeStrategy, _.getUpgradeStrategy.toRaml).orElse(App.DefaultUpgradeStrategy),
      uris = None, // not stored in protobuf
      user = if (service.hasCmd && service.getCmd.hasUser) Option(service.getCmd.getUser) else App.DefaultUser,
      version = version,
      versionInfo = versionInfo, // we restore this but App-to-AppDefinition conversion drops it...
      killSelection = service.whenOrElse(_.hasKillSelection, _.getKillSelection.toRaml, App.DefaultKillSelection),
      unreachableStrategy = service.when(_.hasUnreachableStrategy, _.getUnreachableStrategy.toRaml).orElse(App.DefaultUnreachableStrategy)
    )
    // special ports normalization when converting from protobuf, because the protos don't allow us to distinguish
    // between "I specified an empty set of ports" and "I specified a null set of ports" (for definitions and mappings).
    // note that we don't clear app.container.docker.portMappings because those may be valid in some cases: some other
    // normalization code should deal with that.
    (app.container, app.ipAddress) match {
      case (ct, Some(ip)) if ct.exists(_.`type` != EngineType.Mesos) || ip.discovery.exists(_.ports.nonEmpty) =>
        app.copy(
          container = ct.map(_.copy(portMappings = None)),
          portDefinitions = None,
          requirePorts = false
        )
      case _ =>
        app
    }
  }

  /**
    * @return an app update generated from an app, but without the `version` field (since that can only
    *         be combined with `id` for app updates)
    */
  implicit val appToUpdate: Writes[App, AppUpdate] = Writes { app =>
    AppUpdate(
      id = Some(app.id),
      cmd = app.cmd,
      args = Some(app.args),
      user = app.user,
      env = Some(app.env),
      instances = Some(app.instances),
      cpus = Some(app.cpus),
      mem = Some(app.mem),
      disk = Some(app.disk),
      gpus = Some(app.gpus),
      executor = Some(app.executor),
      constraints = Some(app.constraints),
      fetch = Some(app.fetch),
      storeUrls = Some(app.storeUrls),
      portDefinitions = app.portDefinitions,
      requirePorts = Some(app.requirePorts),
      backoffSeconds = Some(app.backoffSeconds),
      backoffFactor = Some(app.backoffFactor),
      maxLaunchDelaySeconds = Some(app.maxLaunchDelaySeconds),
      container = app.container,
      healthChecks = Some(app.healthChecks),
      readinessChecks = Some(app.readinessChecks),
      taskKillGracePeriodSeconds = app.taskKillGracePeriodSeconds,
      dependencies = Some(app.dependencies),
      upgradeStrategy = app.upgradeStrategy,
      labels = Some(app.labels),
      acceptedResourceRoles = app.acceptedResourceRoles,
      residency = app.residency,
      secrets = Some(app.secrets),
      unreachableStrategy = app.unreachableStrategy,
      killSelection = Some(app.killSelection),
      networks = Some(app.networks),
      // purposefully ignore version since it can only be combined with the `id` field.
      // deprecated fields follow.
      ipAddress = app.ipAddress,
      ports = app.ports,
      uris = app.uris
    )
  }
}

object AppConversion extends AppConversion {

  case class ResidencyAndUpgradeStrategy(residency: Option[Residency], upgradeStrategy: state.UpgradeStrategy)

  object ResidencyAndUpgradeStrategy {
    def apply(
      residency: Option[Residency],
      upgradeStrategy: Option[state.UpgradeStrategy],
      hasPersistentVolumes: Boolean,
      hasExternalVolumes: Boolean): ResidencyAndUpgradeStrategy = {

      import state.UpgradeStrategy.{ empty, forResidentTasks }

      val residencyOrDefault: Option[Residency] =
        residency.orElse(if (hasPersistentVolumes) Some(Residency.default) else None)

      val selectedUpgradeStrategy = upgradeStrategy.getOrElse {
        if (residencyOrDefault.isDefined || hasExternalVolumes) forResidentTasks else empty
      }

      ResidencyAndUpgradeStrategy(residencyOrDefault, selectedUpgradeStrategy)
    }
  }
}
