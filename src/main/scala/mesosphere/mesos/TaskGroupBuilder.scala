package mesosphere.mesos

import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.{ ContainerNetwork, MesosContainer, PodDefinition }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml
import mesosphere.marathon.state.{ EnvVarString, PathId, Timestamp }
import mesosphere.marathon.tasks.PortsMatch
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import org.apache.mesos.{ Protos => mesos }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

// TODO(PODS): Integrate plugin support (e.g. secrets)
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

  case class BuilderConfig(
    acceptedResourceRoles: Set[String],
    envVarsPrefix: Option[String])

  def build(
    podDefinition: PodDefinition,
    offer: mesos.Offer,
    newInstanceId: PathId => Instance.Id,
    config: BuilderConfig
  )(otherInstances: => Seq[Instance]): Option[(mesos.ExecutorInfo, mesos.TaskGroupInfo, Seq[Option[Int]])] = {
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

    resourceMatchOpt.map(build(podDefinition, offer, newInstanceId, config, _)).getOrElse(None)
  }

  private[this] def build(
    podDefinition: PodDefinition,
    offer: mesos.Offer,
    newInstanceId: PathId => Instance.Id,
    config: BuilderConfig,
    resourceMatch: ResourceMatcher.ResourceMatch
  ): Some[(mesos.ExecutorInfo, mesos.TaskGroupInfo, Seq[Option[Int]])] = {
    // TODO: probably set unique ID for each task
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

    val portsEnvVars = portEnvVars(allEndpoints, resourceMatch.hostPorts, envPrefix)

    val taskGroup = mesos.TaskGroupInfo.newBuilder

    podDefinition.containers
      .map(computeTaskInfo(_, podDefinition, offer, instanceId, portsEnvVars))
      .foreach(taskGroup.addTasks)

    Some((executorInfo.build, taskGroup.build, resourceMatch.hostPorts))
  }

  // The resource match provides us with a list of host ports.
  // Each port mapping corresponds to an item in that list.
  // We use that list to swap the dynamic ports (ports set to 0) with the matched ones.
  private[this] def computePortMappings(
    endpoints: Seq[raml.Endpoint],
    hostPorts: Seq[Option[Int]]): Seq[mesos.NetworkInfo.PortMapping] = {

    endpoints.zip(hostPorts).collect {
      case (mapping, Some(hostPort)) =>
        if (mapping.containerPort.isEmpty || mapping.containerPort.get == 0) {
          mesos.NetworkInfo.PortMapping.newBuilder
            .setHostPort(hostPort)
            .setContainerPort(hostPort).build
        } else {
          val portMapping = mesos.NetworkInfo.PortMapping.newBuilder
            .setHostPort(hostPort)
          mapping.containerPort.foreach(portMapping.setContainerPort)

          // TODO(nfnt): set the protocol
          portMapping.build
        }
    }
  }

  private[this] def computeTaskInfo(
    container: MesosContainer,
    podDefinition: PodDefinition,
    offer: mesos.Offer,
    instanceId: Instance.Id,
    portsEnvVars: Map[String, String]): mesos.TaskInfo.Builder = {
    val builder = mesos.TaskInfo.newBuilder
      .setName(container.name)
      .setTaskId(mesos.TaskID.newBuilder.setValue(Task.Id.forInstanceId(instanceId).idString))
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

    computeContainerInfo(podDefinition.podVolumes, container)
      .foreach(builder.setContainer)

    container.healthCheck.foreach { healthCheck =>
      builder.setHealthCheck(computeHealthCheck(healthCheck, container.endpoints))
    }

    builder
  }

  private[this] def computeExecutorInfo(
    podDefinition: PodDefinition,
    portsMatch: PortsMatch,
    portMappings: Seq[mesos.NetworkInfo.PortMapping],
    instanceId: Instance.Id,
    frameworkId: mesos.FrameworkID): mesos.ExecutorInfo.Builder = {
    // TODO: use only an instance id.
    val executorID = mesos.ExecutorID.newBuilder.setValue(instanceId.idString)

    val executorInfo = mesos.ExecutorInfo.newBuilder
      .setType(mesos.ExecutorInfo.Type.DEFAULT)
      .setExecutorId(executorID)
      .setFrameworkId(frameworkId)

    executorInfo.addResources(scalarResource("cpus", PodDefinition.DefaultExecutorCpus))
    executorInfo.addResources(scalarResource("mem", PodDefinition.DefaultExecutorMem))
    executorInfo.addResources(scalarResource("disk", PodDefinition.DefaultExecutorDisk))
    executorInfo.addAllResources(portsMatch.resources.asJava)

    def toMesosLabels(labels: Map[String, String]): mesos.Labels.Builder = {
      labels
        .map{
          case (key, value) =>
            mesos.Label.newBuilder.setKey(key).setValue(value)
        }
        .foldLeft(mesos.Labels.newBuilder) { (builder, label) =>
          builder.addLabels(label)
        }
    }

    if (podDefinition.networks.nonEmpty) {
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
    podVolumes: Seq[raml.Volume],
    container: MesosContainer): Option[mesos.ContainerInfo.Builder] = {
    var containerInfoOpt: Option[mesos.ContainerInfo.Builder] = None

    // Only create a 'ContainerInfo' when some of it's fields are set.
    // Otherwise Mesos will fail to validate it (MESOS-6209).
    def containerInfo: mesos.ContainerInfo.Builder = {
      containerInfoOpt.getOrElse {
        containerInfoOpt = Some(mesos.ContainerInfo.newBuilder.setType(mesos.ContainerInfo.Type.MESOS))
        containerInfoOpt.get
      }
    }

    container.volumeMounts.foreach { volumeMount =>
      podVolumes.find(_.name == volumeMount.name).map { hostVolume =>
        val volume = mesos.Volume.newBuilder
          .setContainerPath(volumeMount.mountPath)

        hostVolume.host.foreach(volume.setHostPath)

        // Read-write mode will be used when the "readOnly" option isn't set.
        val mode = if (volumeMount.readOnly.getOrElse(false)) mesos.Volume.Mode.RO else mesos.Volume.Mode.RW

        volume.setMode(mode)

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

    containerInfoOpt
  }

  private[this] def computeHealthCheck(
    healthCheck: raml.HealthCheck,
    endpoints: Seq[raml.Endpoint]): mesos.HealthCheck.Builder = {
    val builder = mesos.HealthCheck.newBuilder
    builder.setDelaySeconds(healthCheck.delaySeconds.toDouble)
    builder.setGracePeriodSeconds(healthCheck.gracePeriodSeconds.toDouble)
    builder.setIntervalSeconds(healthCheck.intervalSeconds.toDouble)
    builder.setConsecutiveFailures(healthCheck.maxConsecutiveFailures)
    builder.setTimeoutSeconds(healthCheck.timeoutSeconds.toDouble)

    healthCheck.command.foreach { command =>
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

      endpoints.find(_.name == http.endpoint).foreach{ endpoint =>
        // TODO: determine if not in "HOST" mode and use the container port instead
        endpoint.hostPort.foreach(httpCheckInfo.setPort)
      }

      http.scheme.foreach(scheme => httpCheckInfo.setScheme(scheme.value))
      http.path.foreach(httpCheckInfo.setPath)

      builder.setHttp(httpCheckInfo)
    }

    healthCheck.tcp.foreach { tcp =>
      builder.setType(mesos.HealthCheck.Type.TCP)

      val tcpCheckInfo = mesos.HealthCheck.TCPCheckInfo.newBuilder

      endpoints.find(_.name == tcp.endpoint).foreach{ endpoint =>
        // TODO: determine if not in "HOST" mode and use the container port instead
        endpoint.hostPort.foreach(tcpCheckInfo.setPort)
      }

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
