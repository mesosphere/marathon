package mesosphere.mesos

import com.google.protobuf.TextFormat
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon._
import mesosphere.marathon.api.serialization.{ ContainerSerializer, PortDefinitionSerializer, PortMappingSerializer }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.health.HealthCheck
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.state.{ Container, DiscoveryInfo, EnvVarString, IpAddress, PathId, RunSpec }
import mesosphere.mesos.ResourceMatcher.{ ResourceMatch, ResourceSelector }
import org.apache.mesos.Protos.Environment._
import org.apache.mesos.Protos.{ HealthCheck => _, _ }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilder(
    runSpec: RunSpec,
    newTaskId: PathId => Task.Id,
    config: MarathonConf,
    appTaskProc: Option[RunSpecTaskProcessor] = None) {

  import TaskBuilder.log

  def build(
    offer: Offer,
    resourceMatchOpt: Option[ResourceMatcher.ResourceMatch],
    volumeMatchOpt: Option[PersistentVolumeMatcher.VolumeMatch] = None): Option[(TaskInfo, Seq[Option[Int]])] = {

    def logInsufficientResources(): Unit = {
      val runSpecHostPorts = if (runSpec.requirePorts) runSpec.portNumbers else runSpec.portNumbers.map(_ => 0)
      val hostPorts = runSpec.container.flatMap(_.hostPorts).getOrElse(runSpecHostPorts)
      val staticHostPorts = hostPorts.filter(_ != 0)
      val numberDynamicHostPorts = hostPorts.count(_ == 0)

      val maybeStatic: Option[String] = if (staticHostPorts.nonEmpty) {
        Some(s"[${staticHostPorts.mkString(", ")}] required")
      } else {
        None
      }

      val maybeDynamic: Option[String] = if (numberDynamicHostPorts > 0) {
        Some(s"$numberDynamicHostPorts dynamic")
      } else {
        None
      }

      val portStrings = Seq(maybeStatic, maybeDynamic).flatten.mkString(" + ")

      val portsString = s"ports=($portStrings)"

      log.info(
        s"Offer [${offer.getId.getValue}]. Insufficient resources for [${runSpec.id}] " +
          s"(need cpus=${runSpec.resources.cpus}, mem=${runSpec.resources.mem}, disk=${runSpec.resources.disk}, " +
          s"gpus=${runSpec.resources.gpus}, $portsString, available in offer: " +
          s"[${TextFormat.shortDebugString(offer)}]"
      )
    }

    resourceMatchOpt match {
      case Some(resourceMatch) =>
        build(offer, resourceMatch, volumeMatchOpt, appTaskProc)
      case _ =>
        if (log.isInfoEnabled) logInsufficientResources()
        None
    }
  }

  def buildIfMatches(offer: Offer, runningTasks: => Iterable[Task]): Option[(TaskInfo, Seq[Option[Int]])] = {

    val acceptedResourceRoles: Set[String] = {
      val roles = if (runSpec.acceptedResourceRoles.isEmpty) {
        config.defaultAcceptedResourceRolesSet
      } else {
        runSpec.acceptedResourceRoles
      }
      if (log.isDebugEnabled) log.debug(s"acceptedResourceRoles $roles")
      roles
    }

    val resourceMatch =
      ResourceMatcher.matchResources(
        offer, runSpec, runningTasks, ResourceSelector.any(acceptedResourceRoles))

    build(offer, resourceMatch)
  }

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  private[this] def build(
    offer: Offer,
    resourceMatch: ResourceMatch,
    volumeMatchOpt: Option[PersistentVolumeMatcher.VolumeMatch],
    taskBuildOpt: Option[RunSpecTaskProcessor]): Some[(TaskInfo, Seq[Option[Int]])] = {

    val executor: Executor = if (runSpec.executor == "") {
      config.executor
    } else {
      Executor.dispatch(runSpec.executor)
    }

    val host: Option[String] = Some(offer.getHostname)

    val labels = runSpec.labels.map {
      case (key, value) =>
        Label.newBuilder.setKey(key).setValue(value).build()
    }

    val taskId = newTaskId(runSpec.id)
    val builder = TaskInfo.newBuilder
      // Use a valid hostname to make service discovery easier
      .setName(runSpec.id.toHostname)
      .setTaskId(taskId.mesosTaskId)
      .setSlaveId(offer.getSlaveId)
      .addAllResources(resourceMatch.resources.asJava)

    builder.setDiscovery(computeDiscoveryInfo(runSpec, resourceMatch.hostPorts))

    if (labels.nonEmpty)
      builder.setLabels(Labels.newBuilder.addAllLabels(labels.asJava))

    volumeMatchOpt.foreach(_.persistentVolumeResources.foreach(builder.addResources(_)))

    val containerProto = computeContainerInfo(resourceMatch.hostPorts)
    val envPrefix: Option[String] = config.envVarsPrefix.get

    executor match {
      case CommandExecutor() =>
        containerProto.foreach(builder.setContainer)
        val command = TaskBuilder.commandInfo(runSpec, Some(taskId), host, resourceMatch.hostPorts, envPrefix)
        builder.setCommand(command.build)

      case PathExecutor(path) =>
        val executorId = f"marathon-${taskId.idString}" // Fresh executor
        val executorPath = s"'$path'" // TODO: Really escape this.
        val cmd = runSpec.cmd.getOrElse(runSpec.args.mkString(" "))
        val shell = s"chmod ug+rx $executorPath && exec $executorPath $cmd"

        val info = ExecutorInfo.newBuilder()
          .setExecutorId(ExecutorID.newBuilder().setValue(executorId))

        containerProto.foreach(info.setContainer)

        val command =
          TaskBuilder.commandInfo(runSpec, Some(taskId), host, resourceMatch.hostPorts, envPrefix).setValue(shell)
        info.setCommand(command.build)
        builder.setExecutor(info)
    }

    runSpec.taskKillGracePeriod.foreach { period =>
      val durationInfo = DurationInfo.newBuilder.setNanoseconds(period.toNanos)
      val killPolicy = KillPolicy.newBuilder.setGracePeriod(durationInfo)
      builder.setKillPolicy(killPolicy)
    }

    // Mesos supports at most one health check, and only COMMAND checks
    // are currently implemented in the Mesos health check helper program.
    val mesosHealthChecks: Set[org.apache.mesos.Protos.HealthCheck] =
      runSpec.healthChecks.collect {
        case healthCheck: HealthCheck if healthCheck.protocol == Protocol.COMMAND => healthCheck.toMesos
      }(collection.breakOut)

    if (mesosHealthChecks.size > 1) {
      val numUnusedChecks = mesosHealthChecks.size - 1
      log.warn(
        "Mesos supports one command health check per task.\n" +
          s"Task [$taskId] will run without " +
          s"$numUnusedChecks of its defined health checks."
      )
    }

    mesosHealthChecks.headOption.foreach(builder.setHealthCheck)
    taskBuildOpt.foreach(_(runSpec, builder)) // invoke builder plugins

    Some(builder.build -> resourceMatch.hostPorts)
  }

  protected def computeDiscoveryInfo(
    runSpec: RunSpec,
    hostPorts: Seq[Option[Int]]): org.apache.mesos.Protos.DiscoveryInfo = {
    val discoveryInfoBuilder = org.apache.mesos.Protos.DiscoveryInfo.newBuilder
    discoveryInfoBuilder.setName(runSpec.id.toHostname)
    discoveryInfoBuilder.setVisibility(org.apache.mesos.Protos.DiscoveryInfo.Visibility.FRAMEWORK)

    val portProtos = runSpec.ipAddress match {
      case Some(IpAddress(_, _, DiscoveryInfo(ports), _)) if ports.nonEmpty => ports.map(_.toProto)
      case _ =>
        runSpec.container.flatMap(_.portMappings) match {
          case Some(portMappings) =>
            // The run spec uses bridge and user modes with portMappings, use them to create the Port messages
            portMappings.zip(hostPorts).collect {
              case (portMapping, Some(hostPort)) => PortMappingSerializer.toMesosPort(portMapping, hostPort)
            }
          case None =>
            // Serialize runSpec.portDefinitions to protos. The port numbers are the service ports, we need to
            // overwrite them the port numbers assigned to this particular task.
            runSpec.portDefinitions.zip(hostPorts).collect {
              case (portDefinition, Some(hostPort)) =>
                PortDefinitionSerializer.toMesosProto(portDefinition).map(_.toBuilder.setNumber(hostPort).build)
            }.flatten
        }
    }

    val portsProto = org.apache.mesos.Protos.Ports.newBuilder
    portsProto.addAllPorts(portProtos.asJava)
    discoveryInfoBuilder.setPorts(portsProto)

    discoveryInfoBuilder.build
  }

  protected def computeContainerInfo(hostPorts: Seq[Option[Int]]): Option[ContainerInfo] = {
    if (runSpec.container.isEmpty && runSpec.ipAddress.isEmpty) {
      None
    } else {
      val builder = ContainerInfo.newBuilder

      // Fill in Docker container details if necessary
      runSpec.container.foreach { c =>
        // TODO(nfnt): Other containers might also support port mappings in the future.
        // If that is the case, a more general way than the one below needs to be implemented.
        val containerWithPortMappings = c match {
          case docker: Container.Docker => docker.copy(
            portMappings = docker.portMappings.map { pms =>
              pms.zip(hostPorts).collect {
                case (mapping, Some(hport)) =>
                  // Use case: containerPort = 0 and hostPort = 0
                  //
                  // For apps that have their own service registry and require p2p communication,
                  // they will need to advertise
                  // the externally visible ports that their components come up on.
                  // Since they generally know there container port and advertise that, this is
                  // fixed most easily if the container port is the same as the externally visible host
                  // port.
                  if (mapping.containerPort == 0) {
                    mapping.copy(hostPort = Some(hport), containerPort = hport)
                  } else {
                    mapping.copy(hostPort = Some(hport))
                  }
              }
            }
          )
          case _ => c
        }

        builder.mergeFrom(ContainerSerializer.toMesos(containerWithPortMappings))
      }

      // Set NetworkInfo if necessary
      runSpec.ipAddress.foreach { ipAddress =>
        val ipAddressLabels = Labels.newBuilder().addAllLabels(ipAddress.labels.map {
          case (key, value) => Label.newBuilder.setKey(key).setValue(value).build()
        }.asJava)
        val networkInfo: NetworkInfo.Builder =
          NetworkInfo.newBuilder()
            .addAllGroups(ipAddress.groups.asJava)
            .setLabels(ipAddressLabels)
            .addIpAddresses(NetworkInfo.IPAddress.getDefaultInstance)
        ipAddress.networkName.foreach(networkInfo.setName(_))
        builder.addNetworkInfos(networkInfo)
      }

      // Set container type to MESOS by default (this is a required field)
      if (!builder.hasType)
        builder.setType(ContainerInfo.Type.MESOS)

      if (builder.getType.equals(ContainerInfo.Type.MESOS) && !builder.hasMesos) {
        // The comments in "mesos.proto" are fuzzy about whether a miranda MesosInfo
        // is required, but we err on the safe side here and provide one
        builder.setMesos(ContainerInfo.MesosInfo.newBuilder.build)
      }
      Some(builder.build)
    }
  }

}

object TaskBuilder {

  val log = LoggerFactory.getLogger(getClass)

  def commandInfo(
    runSpec: RunSpec,
    taskId: Option[Task.Id],
    host: Option[String],
    hostPorts: Seq[Option[Int]],
    envPrefix: Option[String]): CommandInfo.Builder = {

    val declaredPorts = {
      val containerPorts = for {
        c <- runSpec.container
        pms <- c.portMappings
      } yield pms.map(_.containerPort)

      containerPorts.getOrElse(runSpec.portNumbers)
    }
    val portNames = {
      val containerPortNames = for {
        c <- runSpec.container
        pms <- c.portMappings
      } yield pms.map(_.name)

      containerPortNames.getOrElse(runSpec.portDefinitions.map(_.name))
    }

    val envMap: Map[String, String] =
      taskContextEnv(runSpec, taskId) ++
        addPrefix(envPrefix, EnvironmentHelper.portsEnv(declaredPorts, hostPorts, portNames) ++
          host.map("HOST" -> _).toMap) ++
        runSpec.env.collect{ case (k: String, v: EnvVarString) => k -> v.value }

    val builder = CommandInfo.newBuilder()
      .setEnvironment(environment(envMap))

    runSpec.cmd match {
      case Some(cmd) if cmd.nonEmpty =>
        builder.setValue(cmd)
      case _ =>
        builder.setShell(false)
    }

    // args take precedence over command, if supplied
    if (runSpec.args.nonEmpty) {
      builder.setShell(false)
      builder.addAllArguments(runSpec.args.asJava)
      //mesos command executor expects cmd and arguments
      runSpec.args.headOption.foreach { value =>
        if (runSpec.container.isEmpty) builder.setValue(value)
      }
    }

    if (runSpec.fetch.nonEmpty) {
      builder.addAllUris(runSpec.fetch.map(_.toProto()).asJava)
    }

    runSpec.user.foreach(builder.setUser)

    builder
  }

  def environment(vars: Map[String, String]): Environment = {
    val builder = Environment.newBuilder()

    for ((key, value) <- vars) {
      val variable = Variable.newBuilder().setName(key).setValue(value)
      builder.addVariables(variable)
    }

    builder.build()
  }

  def addPrefix(envVarsPrefix: Option[String], env: Map[String, String]): Map[String, String] = {
    envVarsPrefix match {
      case Some(prefix) => env.map { case (key: String, value: String) => (prefix + key, value) }
      case None => env
    }
  }

  def taskContextEnv(runSpec: RunSpec, taskId: Option[Task.Id]): Map[String, String] = {
    if (taskId.isEmpty) {
      // This branch is taken during serialization. Do not add environment variables in this case.
      Map.empty
    } else {
      Seq(
        "MESOS_TASK_ID" -> taskId.map(_.idString),
        "MARATHON_APP_ID" -> Some(runSpec.id.toString),
        "MARATHON_APP_VERSION" -> Some(runSpec.version.toString),
        "MARATHON_APP_DOCKER_IMAGE" -> runSpec.container.flatMap(_.docker().map(_.image)),
        "MARATHON_APP_RESOURCE_CPUS" -> Some(runSpec.resources.cpus.toString),
        "MARATHON_APP_RESOURCE_MEM" -> Some(runSpec.resources.mem.toString),
        "MARATHON_APP_RESOURCE_DISK" -> Some(runSpec.resources.disk.toString),
        "MARATHON_APP_RESOURCE_GPUS" -> Some(runSpec.resources.gpus.toString)
      ).collect {
          case (key, Some(value)) => key -> value
        }.toMap ++ EnvironmentHelper.labelsToEnvVars(runSpec.labels)
    }
  }
}
