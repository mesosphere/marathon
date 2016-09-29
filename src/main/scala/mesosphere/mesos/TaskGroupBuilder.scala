package mesosphere.mesos

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.raml
import mesosphere.marathon.state.{ EnvVarString, PathId, Timestamp }
import mesosphere.marathon.tasks.PortsMatch
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import org.apache.mesos.{ Protos => mesos }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

object TaskGroupBuilder {
  val log = LoggerFactory.getLogger(getClass)

  // These labels are necessary for AppC images to work.
  // Given that Docker only works under linux with 64bit,
  // let's (for now) set these values to reflect that.
  val LinuxAmd64 = mesos.Labels.newBuilder
    .addAllLabels(
      Iterable(
      mesos.Label.newBuilder.setKey("os").setValue("linux").build,
      mesos.Label.newBuilder.setKey("arch").setValue("amd64").build
    ).asJava)
    .build

  val ephemeralVolPathPrefix = "volumes/"

  case class BuilderConfig(
    acceptedResourceRoles: Set[String],
    envVarsPrefix: Option[String])

  def build(
    podDefinition: PodDefinition,
    offer: mesos.Offer,
    newInstanceId: PathId => Instance.Id,
    config: BuilderConfig,
    runSpecTaskProcessor: RunSpecTaskProcessor = RunSpecTaskProcessor.empty
  )(otherInstances: => Seq[Instance]): Option[(mesos.ExecutorInfo, mesos.TaskGroupInfo, Seq[Option[Int]], Instance.Id)] = {
    val acceptedResourceRoles: Set[String] = {
      val roles = if (podDefinition.acceptedResourceRoles.isEmpty) {
        config.acceptedResourceRoles
      } else {
        podDefinition.acceptedResourceRoles
      }
      if (log.isDebugEnabled) log.debug(s"acceptedResourceRoles $roles")
      roles
    }

    val resourceMatchOpt: Option[ResourceMatcher.ResourceMatch] =
      ResourceMatcher.matchResources(offer, podDefinition, otherInstances, ResourceSelector.any(acceptedResourceRoles))

    resourceMatchOpt.map(build(podDefinition, offer, newInstanceId, config, runSpecTaskProcessor, _)).getOrElse(None)
  }

  private[this] def build(
    podDefinition: PodDefinition,
    offer: mesos.Offer,
    newInstanceId: PathId => Instance.Id,
    config: BuilderConfig,
    runSpecTaskProcessor: RunSpecTaskProcessor,
    resourceMatch: ResourceMatcher.ResourceMatch
  ): Some[(mesos.ExecutorInfo, mesos.TaskGroupInfo, Seq[Option[Int]], Instance.Id)] = {
    val instanceId = newInstanceId(podDefinition.id)

    val allEndpoints = for {
      container <- podDefinition.containers
      endpoint <- container.endpoints
    } yield endpoint

    val portMappings = computePortMappings(allEndpoints, resourceMatch.hostPorts)

    val executorInfo = computeExecutorInfo(
      podDefinition,
      resourceMatch.portsMatch,
      portMappings,
      instanceId,
      offer.getFrameworkId)

    val envPrefix: Option[String] = config.envVarsPrefix

    val taskGroup = mesos.TaskGroupInfo.newBuilder

    podDefinition.containers
      .map(computeTaskInfo(_, podDefinition, offer, instanceId, resourceMatch.hostPorts, config))
      .foreach(taskGroup.addTasks)

    // call all configured run spec customizers here (plugin)
    runSpecTaskProcessor.taskGroup(podDefinition, taskGroup)

    Some((executorInfo.build, taskGroup.build, resourceMatch.hostPorts, instanceId))
  }

  // The resource match provides us with a list of host ports.
  // Each port mapping corresponds to an item in that list.
  // We use that list to swap the dynamic ports (ports set to 0) with the matched ones.
  @SuppressWarnings(Array("OptionGet"))
  private[this] def computePortMappings(
    endpoints: Seq[raml.Endpoint],
    hostPorts: Seq[Option[Int]]): Seq[mesos.NetworkInfo.PortMapping] = {

    endpoints.zip(hostPorts).collect {
      case (endpoint, Some(hostPort)) =>
        val portMapping = mesos.NetworkInfo.PortMapping.newBuilder
          .setHostPort(hostPort)

        if (endpoint.containerPort.isEmpty || endpoint.containerPort.get == 0) {
          portMapping.setContainerPort(hostPort)
        } else {
          endpoint.containerPort.foreach(portMapping.setContainerPort)
        }

        // While the protocols in RAML may be declared in a list, Mesos expects a
        // port mapping for every single protocol. If protocols are set, a port mapping
        // will be created for every protocol in the list.
        if (endpoint.protocol.isEmpty) {
          Seq(portMapping.build)
        } else {
          endpoint.protocol.map { protocol =>
            portMapping.setProtocol(protocol).build
          }
        }
    }.flatten
  }

  private[this] def computeTaskInfo(
    container: MesosContainer,
    podDefinition: PodDefinition,
    offer: mesos.Offer,
    instanceId: Instance.Id,
    hostPorts: Seq[Option[Int]],
    config: BuilderConfig): mesos.TaskInfo.Builder = {

    //compute task local port env vars
    val allEndpoints = podDefinition.containers.flatMap(_.endpoints.map(_.name)).zip(hostPorts).toMap.withDefaultValue(None)
    val containerEndpoints = allEndpoints.filter{ case (name, port) => container.endpoints.exists(_.name == name) }
    val portsEnvVars = portEnvVars(container.endpoints, containerEndpoints.values.toVector, config.envVarsPrefix)

    val builder = mesos.TaskInfo.newBuilder
      .setName(container.name)
      .setTaskId(mesos.TaskID.newBuilder.setValue(Task.Id.forInstanceId(instanceId, Some(container)).idString))
      .setSlaveId(offer.getSlaveId)

    builder.addResources(scalarResource("cpus", container.resources.cpus))
    builder.addResources(scalarResource("mem", container.resources.mem))
    builder.addResources(scalarResource("disk", container.resources.disk))
    builder.addResources(scalarResource("gpus", container.resources.gpus.toDouble))

    if (container.labels.nonEmpty)
      builder.setLabels(mesos.Labels.newBuilder.addAllLabels(container.labels.map {
        case (key, value) =>
          mesos.Label.newBuilder.setKey(key).setValue(value).build
      }.asJava))

    val commandInfo = computeCommandInfo(
      podDefinition,
      instanceId,
      container,
      offer.getHostname,
      portsEnvVars)

    builder.setCommand(commandInfo)

    computeContainerInfo(podDefinition.volume, container)
      .foreach(builder.setContainer)

    container.healthCheck.foreach { healthCheck =>
      builder.setHealthCheck(computeHealthCheck(healthCheck, podDefinition, hostPorts))
    }

    builder
  }

  private[this] def computeExecutorInfo(
    podDefinition: PodDefinition,
    portsMatch: PortsMatch,
    portMappings: Seq[mesos.NetworkInfo.PortMapping],
    instanceId: Instance.Id,
    frameworkId: mesos.FrameworkID): mesos.ExecutorInfo.Builder = {
    val executorID = mesos.ExecutorID.newBuilder.setValue(instanceId.executorIdString)

    val executorInfo = mesos.ExecutorInfo.newBuilder
      .setType(mesos.ExecutorInfo.Type.DEFAULT)
      .setExecutorId(executorID)
      .setFrameworkId(frameworkId)

    executorInfo.addResources(scalarResource("cpus", PodDefinition.DefaultExecutorResources.cpus))
    executorInfo.addResources(scalarResource("mem", PodDefinition.DefaultExecutorResources.mem))
    executorInfo.addResources(scalarResource("disk", PodDefinition.DefaultExecutorResources.disk))
    executorInfo.addResources(scalarResource("gpus", PodDefinition.DefaultExecutorResources.gpus.toDouble))
    executorInfo.addAllResources(portsMatch.resources.asJava)

    def toMesosLabels(labels: Map[String, String]): mesos.Labels.Builder = {
      labels.map{
        case (key, value) =>
          mesos.Label.newBuilder.setKey(key).setValue(value)
      }.foldLeft(mesos.Labels.newBuilder) { (builder, label) =>
        builder.addLabels(label)
      }
    }

    if (podDefinition.networks.nonEmpty || podDefinition.volumes.nonEmpty) {
      val containerInfo = mesos.ContainerInfo.newBuilder
        .setType(mesos.ContainerInfo.Type.MESOS)

      // TODO: Does 'DiscoveryInfo' need to be set?

      podDefinition.networks.collect{
        case containerNetwork: ContainerNetwork =>
          mesos.NetworkInfo.newBuilder
            .setName(containerNetwork.name)
            .setLabels(toMesosLabels(containerNetwork.labels))
            .addAllPortMappings(portMappings.asJava)
      }.foreach{ networkInfo =>
        containerInfo.addNetworkInfos(networkInfo)
      }

      podDefinition.volumes.collect{
        // see the related code in computeContainerInfo
        case e: EphemeralVolume =>
          mesos.Volume.newBuilder()
            .setMode(mesos.Volume.Mode.RW) // if not RW, then how do containers plan to share anything?
            .setSource(mesos.Volume.Source.newBuilder()
              .setType(mesos.Volume.Source.Type.SANDBOX_PATH)
              .setSandboxPath(mesos.Volume.Source.SandboxPath.newBuilder()
                .setType(mesos.Volume.Source.SandboxPath.Type.SELF)
                .setPath(ephemeralVolPathPrefix + e.name) // matches the path in computeContainerInfo
              ))
      }.foreach{
        volume => containerInfo.addVolumes(volume)
      }

      executorInfo.setContainer(containerInfo)
    }

    executorInfo.setLabels(toMesosLabels(podDefinition.labels))

    executorInfo
  }

  private[this] def computeCommandInfo(
    podDefinition: PodDefinition,
    instanceId: Instance.Id,
    container: MesosContainer,
    host: String,
    portsEnvVars: Map[String, String]): mesos.CommandInfo.Builder = {
    val commandInfo = mesos.CommandInfo.newBuilder

    container.exec.foreach{ exec =>
      exec.command match {
        case raml.ShellCommand(shell) =>
          commandInfo.setShell(true)
          commandInfo.setValue(shell)
        case raml.ArgvCommand(argv) =>
          commandInfo.setShell(false)
          commandInfo.addAllArguments(argv.asJava)
          if (exec.overrideEntrypoint.getOrElse(false)) {
            argv.headOption.foreach(commandInfo.setValue)
          }
      }
    }

    // Container user overrides pod user
    val user = container.user.orElse(podDefinition.user)
    user.foreach(commandInfo.setUser)

    val uris = container.artifacts.map { artifact =>
      val uri = mesos.CommandInfo.URI.newBuilder.setValue(artifact.uri)

      artifact.cache.foreach(uri.setCache)
      artifact.extract.foreach(uri.setExtract)
      artifact.executable.foreach(uri.setExecutable)
      artifact.destPath.foreach(uri.setOutputFile)

      uri.build
    }

    commandInfo.addAllUris(uris.asJava)

    val podEnvVars = podDefinition.env.collect{ case (k: String, v: EnvVarString) => k -> v.value }

    val taskEnvVars = container.env.collect{ case (k: String, v: EnvVarString) => k -> v.value }

    val hostEnvVar = Map("HOST" -> host)

    val taskContextEnvVars = taskContextEnv(container, podDefinition.version, instanceId)

    val labels = podDefinition.labels ++ container.labels

    val labelEnvVars = EnvironmentHelper.labelsToEnvVars(labels)

    // Variables defined on task level should override ones defined at pod level.
    // Therefore the order here is important. Values for existing keys will be overwritten in the order they are added.
    val envVars = (podEnvVars ++
      taskEnvVars ++
      hostEnvVar ++
      taskContextEnvVars ++
      labelEnvVars ++
      portsEnvVars)
      .map {
        case (name, value) =>
          mesos.Environment.Variable.newBuilder.setName(name).setValue(value).build
      }

    commandInfo.setEnvironment(mesos.Environment.newBuilder.addAllVariables(envVars.asJava))
  }

  private[this] def computeContainerInfo(
    volumeForName: String => Volume,
    container: MesosContainer): Option[mesos.ContainerInfo.Builder] = {

    val containerInfo = mesos.ContainerInfo.newBuilder.setType(mesos.ContainerInfo.Type.MESOS)

    container.volumeMounts.foreach { volumeMount =>

      // Read-write mode will be used when the "readOnly" option isn't set.
      val mode = if (volumeMount.readOnly.getOrElse(false)) mesos.Volume.Mode.RO else mesos.Volume.Mode.RW

      volumeForName(volumeMount.name) match {
        case hostVolume: HostVolume =>
          val volume = mesos.Volume.newBuilder()
            .setMode(mode)
            .setContainerPath(volumeMount.mountPath)
            // TODO(jdef) use source type HOST_PATH once it's available (this will soon be deprecated)
            .setHostPath(hostVolume.hostPath)

          containerInfo.addVolumes(volume)

        case e: EphemeralVolume =>
          // see the related code in computeExecutorInfo
          val volume = mesos.Volume.newBuilder()
            .setMode(mode)
            .setContainerPath(volumeMount.mountPath)
            .setSource(mesos.Volume.Source.newBuilder()
              .setType(mesos.Volume.Source.Type.SANDBOX_PATH)
              .setSandboxPath(mesos.Volume.Source.SandboxPath.newBuilder()
                .setType(mesos.Volume.Source.SandboxPath.Type.PARENT)
                .setPath(ephemeralVolPathPrefix + volumeMount.name)
              ))

          containerInfo.addVolumes(volume)
      }
    }

    container.image.foreach { im =>
      val image = mesos.Image.newBuilder

      im.forcePull.foreach(forcePull => image.setCached(!forcePull))

      im.kind match {
        case raml.ImageType.Docker =>
          val docker = mesos.Image.Docker.newBuilder.setName(im.id)

          image.setType(mesos.Image.Type.DOCKER).setDocker(docker)
        case raml.ImageType.Appc =>
          val appc = mesos.Image.Appc.newBuilder.setName(im.id).setLabels(LinuxAmd64)

          image.setType(mesos.Image.Type.APPC).setAppc(appc)
      }

      val mesosInfo = mesos.ContainerInfo.MesosInfo.newBuilder.setImage(image)
      containerInfo.setMesos(mesosInfo)
    }

    // Only create a 'ContainerInfo' when some of it's fields are set.
    // If no fields other than the type have been set, then we shouldn't pass the container info
    if (mesos.ContainerInfo.newBuilder.setType(mesos.ContainerInfo.Type.MESOS).build() == containerInfo.build()) {
      None
    } else {
      Some(containerInfo)
    }
  }

  private[this] def computeHealthCheck(
    healthCheck: raml.HealthCheck,
    podDefinition: PodDefinition,
    hostPorts: Seq[Option[Int]]): mesos.HealthCheck.Builder = {

    val builder = mesos.HealthCheck.newBuilder
    builder.setDelaySeconds(healthCheck.delaySeconds.toDouble)
    builder.setGracePeriodSeconds(healthCheck.gracePeriodSeconds.toDouble)
    builder.setIntervalSeconds(healthCheck.intervalSeconds.toDouble)
    builder.setConsecutiveFailures(healthCheck.maxConsecutiveFailures)
    builder.setTimeoutSeconds(healthCheck.timeoutSeconds.toDouble)

    lazy val hostPortsByEndpoint: Map[String, Option[Int]] = {
      podDefinition.containers.flatMap(_.endpoints.map(_.name)).zip(hostPorts).toMap.withDefaultValue(None)
    }

    assume(
      hostPorts.size == hostPortsByEndpoint.size,
      s"Endpoints without resolved host ports: ${hostPorts.size} byEndpoint: ${hostPortsByEndpoint.size}")

    healthCheck.exec.foreach { command =>
      builder.setType(mesos.HealthCheck.Type.COMMAND)

      val commandInfo = mesos.CommandInfo.newBuilder

      command.command match {
        case raml.ShellCommand(shell) =>
          commandInfo.setShell(true)
          commandInfo.setValue(shell)
        case raml.ArgvCommand(argv) =>
          commandInfo.setShell(false)
          commandInfo.addAllArguments(argv.asJava)
          argv.headOption.foreach(commandInfo.setValue)
      }

      builder.setCommand(commandInfo)
    }

    healthCheck.http.foreach { http =>
      builder.setType(mesos.HealthCheck.Type.HTTP)
      val httpCheckInfo = mesos.HealthCheck.HTTPCheckInfo.newBuilder
      // TODO: determine if not in "HOST" mode and use the container port instead
      hostPortsByEndpoint(http.endpoint).foreach(httpCheckInfo.setPort)
      http.scheme.foreach(scheme => httpCheckInfo.setScheme(scheme.value))
      http.path.foreach(httpCheckInfo.setPath)
      builder.setHttp(httpCheckInfo)
    }

    healthCheck.tcp.foreach { tcp =>
      builder.setType(mesos.HealthCheck.Type.TCP)
      val tcpCheckInfo = mesos.HealthCheck.TCPCheckInfo.newBuilder
      // TODO: determine if not in "HOST" mode and use the container port instead
      hostPortsByEndpoint(tcp.endpoint).foreach(tcpCheckInfo.setPort)
      builder.setTcp(tcpCheckInfo)
    }

    builder
  }

  private[this] def portEnvVars(
    endpoints: Seq[raml.Endpoint],
    hostPorts: Seq[Option[Int]],
    envPrefix: Option[String]): Map[String, String] = {
    // TODO(nfnt): Refactor this to use portMappings
    val declaredPorts = endpoints.flatMap(_.containerPort)
    val portNames = endpoints.map(endpoint => Some(endpoint.name))

    val portEnvVars = EnvironmentHelper.portsEnv(declaredPorts, hostPorts, portNames)

    envPrefix match {
      case Some(prefix) =>
        portEnvVars.map{ case (key, value) => (prefix + key, value) }
      case None =>
        portEnvVars
    }
  }

  private[this] def taskContextEnv(
    container: MesosContainer,
    version: Timestamp,
    instanceId: Instance.Id): Map[String, String] = {
    Map(
      "MESOS_TASK_ID" -> Some(instanceId.idString),
      "MARATHON_APP_ID" -> Some(instanceId.runSpecId.toString),
      "MARATHON_APP_VERSION" -> Some(version.toString),
      "MARATHON_CONTAINER_ID" -> Some(container.name),
      "MARATHON_CONTAINER_RESOURCE_CPUS" -> Some(container.resources.cpus.toString),
      "MARATHON_CONTAINER_RESOURCE_MEM" -> Some(container.resources.mem.toString),
      "MARATHON_CONTAINER_RESOURCE_DISK" -> Some(container.resources.disk.toString),
      "MARATHON_CONTAINER_RESOURCE_GPUS" -> Some(container.resources.gpus.toString)
    ).collect {
        case (key, Some(value)) => key -> value
      }
  }

  private[this] def scalarResource(name: String, value: Double): mesos.Resource.Builder = {
    mesos.Resource.newBuilder
      .setName(name)
      .setType(mesos.Value.Type.SCALAR)
      .setScalar(mesos.Value.Scalar.newBuilder.setValue(value))
  }
}
