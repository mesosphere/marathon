package mesosphere.mesos

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.health.{ MesosCommandHealthCheck, MesosHealthCheck }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.raml
import mesosphere.marathon.raml.Endpoint
import mesosphere.marathon.state.{ EnvVarString, PathId, PortAssignment, Timestamp }
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.tasks.PortsMatch
import mesosphere.mesos.protos.Implicits._
import org.apache.mesos.{ Protos => mesos }

import scala.collection.immutable.Seq

object TaskGroupBuilder extends StrictLogging {

  // These labels are necessary for AppC images to work.
  // Given that Docker only works under linux with 64bit,
  // let's (for now) set these values to reflect that.
  protected[mesos] val LinuxAmd64 = Map("os" -> "linux", "arch" -> "amd64")

  private val ephemeralVolPathPrefix = "volumes/"

  case class BuilderConfig(
    acceptedResourceRoles: Set[String],
    envVarsPrefix: Option[String])

  def build(
    podDefinition: PodDefinition,
    offer: mesos.Offer,
    newInstanceId: PathId => Instance.Id,
    config: BuilderConfig,
    runSpecTaskProcessor: RunSpecTaskProcessor,
    resourceMatch: ResourceMatcher.ResourceMatch
  ): (mesos.ExecutorInfo, mesos.TaskGroupInfo, Seq[Option[Int]], Instance.Id) = {
    val instanceId = newInstanceId(podDefinition.id)

    val allEndpoints = podDefinition.containers.flatMap(_.endpoints)

    assume(
      allEndpoints.size == resourceMatch.hostPorts.size,
      "expected total number of endpoints to match number of optional host ports")

    val portMappings = computePortMappings(allEndpoints, resourceMatch.hostPorts)

    val executorInfo = computeExecutorInfo(
      podDefinition,
      resourceMatch.portsMatch,
      portMappings,
      instanceId,
      offer.getFrameworkId)

    val endpointAllocationsPerContainer: Map[String, Seq[Endpoint]] =
      podDefinition.containers.flatMap { c =>
        c.endpoints.map(c.name -> _)
      }.zip(resourceMatch.hostPorts).groupBy { case (t, _) => t._1 }.map {
        case (k, s) =>
          k -> s.map { case (t, hp) => t._2.copy(hostPort = hp) }
      }

    val taskGroup = mesos.TaskGroupInfo.newBuilder.addAllTasks(
      podDefinition.containers.map { container =>
        val endpoints = endpointAllocationsPerContainer.getOrElse(container.name, Nil)
        val portAssignments = computePortAssignments(podDefinition, endpoints)

        computeTaskInfo(container, podDefinition, offer, instanceId, resourceMatch.hostPorts, config, portAssignments)
          .setDiscovery(taskDiscovery(podDefinition, endpoints))
          .build
      }
    )

    // call all configured run spec customizers here (plugin)
    runSpecTaskProcessor.taskGroup(podDefinition, executorInfo, taskGroup)

    (executorInfo.build, taskGroup.build, resourceMatch.hostPorts, instanceId)
  }

  // The resource match provides us with a list of host ports.
  // Each port mapping corresponds to an item in that list.
  // We use that list to swap the dynamic ports (ports set to 0) with the matched ones.
  private[this] def computePortMappings(
    endpoints: Seq[raml.Endpoint],
    hostPorts: Seq[Option[Int]]): Seq[mesos.NetworkInfo.PortMapping] = {

    endpoints.zip(hostPorts).collect {
      case (endpoint, Some(hostPort)) =>
        val portMapping = mesos.NetworkInfo.PortMapping.newBuilder
          .setHostPort(hostPort)

        if (endpoint.containerPort.forall(_ == 0)) {
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
    config: BuilderConfig,
    portAssignments: Seq[PortAssignment]): mesos.TaskInfo.Builder = {

    val endpointVars = endpointEnvVars(podDefinition, hostPorts, config)

    val taskId = Task.Id.forInstanceId(instanceId, Some(container))

    val builder = mesos.TaskInfo.newBuilder
      .setName(container.name)
      .setTaskId(mesos.TaskID.newBuilder.setValue(taskId.idString))
      .setSlaveId(offer.getSlaveId)

    builder.addResources(scalarResource("cpus", container.resources.cpus))
    builder.addResources(scalarResource("mem", container.resources.mem))
    builder.addResources(scalarResource("disk", container.resources.disk))
    builder.addResources(scalarResource("gpus", container.resources.gpus.toDouble))

    if (container.labels.nonEmpty)
      builder.setLabels(mesos.Labels.newBuilder.addAllLabels(container.labels.map {
        case (key, value) =>
          mesos.Label.newBuilder.setKey(key).setValue(value).build
      }))

    val commandInfo = computeCommandInfo(
      podDefinition,
      instanceId,
      taskId,
      container,
      offer.getHostname,
      endpointVars)

    builder.setCommand(commandInfo)

    computeContainerInfo(podDefinition, container)
      .foreach(builder.setContainer)

    container.healthCheck.foreach { healthCheck =>
      computeHealthCheck(healthCheck, portAssignments).foreach(builder.setHealthCheck)
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

    executorInfo.addResources(scalarResource("cpus", podDefinition.executorResources.cpus))
    executorInfo.addResources(scalarResource("mem", podDefinition.executorResources.mem))
    executorInfo.addResources(scalarResource("disk", podDefinition.executorResources.disk))
    executorInfo.addResources(scalarResource("gpus", podDefinition.executorResources.gpus.toDouble))
    executorInfo.addAllResources(portsMatch.resources)

    if (podDefinition.networks.nonEmpty || podDefinition.volumes.nonEmpty) {
      val containerInfo = mesos.ContainerInfo.newBuilder
        .setType(mesos.ContainerInfo.Type.MESOS)

      podDefinition.networks.collect{
        case containerNetwork: ContainerNetwork =>
          mesos.NetworkInfo.newBuilder
            .setName(containerNetwork.name)
            .setLabels(containerNetwork.labels.toMesosLabels)
            .addAllPortMappings(portMappings)
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

    executorInfo.setLabels(podDefinition.labels.toMesosLabels)

    executorInfo
  }

  private[this] def computeCommandInfo(
    podDefinition: PodDefinition,
    instanceId: Instance.Id,
    taskId: Task.Id,
    container: MesosContainer,
    host: String,
    portsEnvVars: Map[String, String]): mesos.CommandInfo.Builder = {
    val commandInfo = mesos.CommandInfo.newBuilder

    // By default 'shell' is set to true which will result in an error if the user
    // wants to use Entrypoint/Cmd of Docker images. This is documented in
    // http://mesos.apache.org/documentation/latest/mesos-containerizer/
    // Setting it to false here will allow Entrypoint/Cmd values to work.
    commandInfo.setShell(false)

    container.exec.foreach{ exec =>
      exec.command match {
        case raml.ShellCommand(shell) =>
          commandInfo.setShell(true)
          commandInfo.setValue(shell)
        case raml.ArgvCommand(argv) =>
          commandInfo.setShell(false)
          commandInfo.addAllArguments(argv)
          if (exec.overrideEntrypoint.getOrElse(false)) {
            argv.headOption.foreach(commandInfo.setValue)
          }
      }
    }

    // Container user overrides pod user
    val user = container.user.orElse(podDefinition.user)
    user.foreach(commandInfo.setUser)

    val uris = container.artifacts.map { artifact =>
      val uri = mesos.CommandInfo.URI.newBuilder
        .setValue(artifact.uri)
        .setCache(artifact.cache)
        .setExtract(artifact.extract)
        .setExecutable(artifact.executable)

      artifact.destPath.foreach(uri.setOutputFile)

      uri.build
    }

    commandInfo.addAllUris(uris)

    val podEnvVars = podDefinition.env.collect{ case (k: String, v: EnvVarString) => k -> v.value }

    val taskEnvVars = container.env.collect{ case (k: String, v: EnvVarString) => k -> v.value }

    val hostEnvVar = Map("HOST" -> host)

    val taskContextEnvVars = taskContextEnv(container, podDefinition.version, instanceId, taskId)

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

    commandInfo.setEnvironment(mesos.Environment.newBuilder.addAllVariables(envVars))
  }

  private[this] def computeContainerInfo(
    podDefinition: PodDefinition,
    container: MesosContainer): Option[mesos.ContainerInfo.Builder] = {

    val containerInfo = mesos.ContainerInfo.newBuilder.setType(mesos.ContainerInfo.Type.MESOS)

    container.volumeMounts.foreach { volumeMount =>

      // Read-write mode will be used when the "readOnly" option isn't set.
      val mode = if (volumeMount.readOnly.getOrElse(false)) mesos.Volume.Mode.RO else mesos.Volume.Mode.RW

      podDefinition.volume(volumeMount.name) match {
        case hostVolume: HostVolume =>
          val volume = mesos.Volume.newBuilder()
            .setMode(mode)
            .setContainerPath(volumeMount.mountPath)
            // TODO(jdef) use source type HOST_PATH once it's available (this will soon be deprecated)
            .setHostPath(hostVolume.hostPath)

          containerInfo.addVolumes(volume)

        case _: EphemeralVolume =>
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
          val appcLabels = (LinuxAmd64 ++ im.labels).toMesosLabels
          val appc = mesos.Image.Appc.newBuilder.setName(im.id).setLabels(appcLabels)
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

  /**
    * @param podDefinition is queried to determine networking mode
    * @param endpoints are assumed to have had all wildcard ports (e.g. 0) filled in with real values
    */
  private[this] def computePortAssignments(
    podDefinition: PodDefinition,
    endpoints: Seq[Endpoint]): Seq[PortAssignment] = {

    val isHostModeNetworking = podDefinition.networks.contains(HostNetwork)

    endpoints.map { ep =>
      PortAssignment(
        portName = Some(ep.name),
        hostPort = ep.hostPort.find(_ => isHostModeNetworking),
        containerPort = ep.containerPort.find(_ => !isHostModeNetworking),
        // we don't need these for health checks proto generation, presumably because we can't definitively know,
        // in all cases, the full network address of the health check until the task is actually launched.
        effectiveIpAddress = None,
        effectivePort = PortAssignment.NoPort
      )
    }
  }

  private[this] def computeHealthCheck(
    healthCheck: MesosHealthCheck,
    portAssignments: Seq[PortAssignment]): Option[mesos.HealthCheck] = {

    healthCheck match {
      case _: MesosCommandHealthCheck =>
        healthCheck.toMesos()
      case _ =>
        healthCheck.toMesos(portAssignments)
    }
  }

  /**
    * Computes all endpoint env vars for the entire pod definition
    * Form:
    * ENDPOINT_{ENDPOINT_NAME}=123
    */
  private[this] def endpointEnvVars(
    pod: PodDefinition,
    hostPorts: Seq[Option[Int]],
    builderConfig: BuilderConfig): Map[String, String] = {
    val prefix = builderConfig.envVarsPrefix.getOrElse("").toUpperCase
    def escape(name: String): String = name.replaceAll("[^A-Z0-9_]+", "_").toUpperCase

    val hostNetwork = pod.networks.contains(HostNetwork)
    val hostPortByEndpoint = pod.containers.view.flatMap(_.endpoints).zip(hostPorts).toMap.withDefaultValue(None)
    pod.containers.view.flatMap(_.endpoints).flatMap{ endpoint =>
      val mayBePort = if (hostNetwork) hostPortByEndpoint(endpoint) else endpoint.containerPort
      val envName = escape(endpoint.name.toUpperCase)
      Seq(
        mayBePort.map(p => s"${prefix}ENDPOINT_$envName" -> p.toString),
        hostPortByEndpoint(endpoint).map(p => s"${prefix}EP_HOST_$envName" -> p.toString),
        endpoint.containerPort.map(p => s"${prefix}EP_CONTAINER_$envName" -> p.toString)
      ).flatten
    }.toMap
  }

  private[this] def taskContextEnv(
    container: MesosContainer,
    version: Timestamp,
    instanceId: Instance.Id,
    taskId: Task.Id): Map[String, String] = {
    Map(
      "MESOS_TASK_ID" -> Some(taskId.idString),
      "MESOS_EXECUTOR_ID" -> Some(instanceId.executorIdString),
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

  private def taskDiscovery(pod: PodDefinition, endpoints: Seq[Endpoint]): mesos.DiscoveryInfo = {
    val ports = PortDiscovery.generate(pod.networks.contains(HostNetwork), endpoints)
    mesos.DiscoveryInfo.newBuilder.setPorts(mesos.Ports.newBuilder.addAllPorts(ports))
      .setName(pod.id.toHostname)
      .setVisibility(org.apache.mesos.Protos.DiscoveryInfo.Visibility.FRAMEWORK)
      .build
  }
}
