package mesosphere.mesos

import com.google.protobuf.{ ByteString, TextFormat }
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon._
import mesosphere.marathon.api.serialization.{ PortMappingSerializer, PortDefinitionSerializer, ContainerSerializer }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.{ PersistentVolume, ExternalVolume, AppDefinition, DiscoveryInfo, IpAddress, PathId }
import mesosphere.mesos.ResourceMatcher.{ ResourceSelector, ResourceMatch }
import mesosphere.mesos.protos.{ RangesResource, Resource, ScalarResource }
import org.apache.mesos.Protos.Environment._
import org.apache.mesos.Protos.{ HealthCheck => _, _ }
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilder(app: AppDefinition,
                  newTaskId: PathId => Task.Id,
                  config: MarathonConf) {

  val log = LoggerFactory.getLogger(getClass)

  def build(
    offer: Offer,
    resourceMatchOpt: Option[ResourceMatcher.ResourceMatch],
    volumeMatchOpt: Option[PersistentVolumeMatcher.VolumeMatch] = None): Option[(TaskInfo, Seq[Int])] = {

    def logInsufficientResources(): Unit = {
      val appHostPorts = if (app.requirePorts) app.portNumbers else app.portNumbers.map(_ => 0)
      val containerHostPorts: Option[Seq[Int]] = app.containerHostPorts
      val hostPorts = containerHostPorts.getOrElse(appHostPorts)
      val staticHostPorts = hostPorts.filter(_ != 0)
      val numberDynamicHostPorts = hostPorts.count(_ == 0)

      val maybeStatic: Option[String] = if (staticHostPorts.nonEmpty) {
        Some(s"[${staticHostPorts.mkString(", ")}] required")
      }
      else {
        None
      }

      val maybeDynamic: Option[String] = if (numberDynamicHostPorts > 0) {
        Some(s"$numberDynamicHostPorts dynamic")
      }
      else {
        None
      }

      val portStrings = Seq(maybeStatic, maybeDynamic).flatten.mkString(" + ")

      val portsString = s"ports=($portStrings)"

      log.info(
        s"Offer [${offer.getId.getValue}]. Insufficient resources for [${app.id}] (need cpus=${app.cpus}, " +
          s"mem=${app.mem}, disk=${app.disk}, $portsString, available in offer: " +
          s"[${TextFormat.shortDebugString(offer)}]"
      )
    }

    resourceMatchOpt match {
      case Some(resourceMatch) =>
        build(offer, resourceMatch, volumeMatchOpt)
      case _ =>
        if (log.isInfoEnabled) logInsufficientResources()
        None
    }
  }

  def buildIfMatches(offer: Offer, runningTasks: => Iterable[Task]): Option[(TaskInfo, Seq[Int])] = {

    val acceptedResourceRoles: Set[String] = {
      val roles = app.acceptedResourceRoles.getOrElse(config.defaultAcceptedResourceRolesSet)
      if (log.isDebugEnabled) log.debug(s"acceptedResourceRoles $roles")
      roles
    }

    val resourceMatch =
      ResourceMatcher.matchResources(
        offer, app, runningTasks, ResourceSelector(acceptedResourceRoles, reserved = false))

    build(offer, resourceMatch)
  }

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  private[this] def build(
    offer: Offer,
    resourceMatch: ResourceMatch,
    volumeMatchOpt: Option[PersistentVolumeMatcher.VolumeMatch]): Some[(TaskInfo, Seq[Int])] = {

    val executor: Executor = if (app.executor == "") {
      config.executor
    }
    else {
      Executor.dispatch(app.executor)
    }

    val host: Option[String] = Some(offer.getHostname)

    val labels = app.labels.map {
      case (key, value) =>
        Label.newBuilder.setKey(key).setValue(value).build()
    }

    val taskId = newTaskId(app.id)
    val builder = TaskInfo.newBuilder
      // Use a valid hostname to make service discovery easier
      .setName(app.id.toHostname)
      .setTaskId(taskId.mesosTaskId)
      .setSlaveId(offer.getSlaveId)
      .addAllResources(resourceMatch.resources.asJava)

    builder.setDiscovery(computeDiscoveryInfo(app, resourceMatch.hostPorts))

    if (labels.nonEmpty)
      builder.setLabels(Labels.newBuilder.addAllLabels(labels.asJava))

    volumeMatchOpt.foreach(_.persistentVolumeResources.foreach(builder.addResources(_)))

    val containerProto = computeContainerInfo(resourceMatch.hostPorts)
    val envPrefix: Option[String] = config.envVarsPrefix.get

    def decorateForExternalVolumes(builder: CommandInfo.Builder) = containerProto.foreach { cp =>
      app.externalVolumes.foreach {
        ExternalVolumes.build(cp.getType, builder, _)
      }
    }

    executor match {
      case CommandExecutor() =>
        containerProto.foreach(builder.setContainer)
        val command = TaskBuilder.commandInfo(app, Some(taskId), host, resourceMatch.hostPorts, envPrefix)
        decorateForExternalVolumes(command)
        builder.setCommand(command.build)

      case PathExecutor(path) =>
        val executorId = f"marathon-${taskId.idString}" // Fresh executor
        val executorPath = s"'$path'" // TODO: Really escape this.
        val cmd = app.cmd orElse app.args.map(_ mkString " ") getOrElse ""
        val shell = s"chmod ug+rx $executorPath && exec $executorPath $cmd"

        val info = ExecutorInfo.newBuilder()
          .setExecutorId(ExecutorID.newBuilder().setValue(executorId))

        containerProto.foreach(info.setContainer)

        val command =
          TaskBuilder.commandInfo(app, Some(taskId), host, resourceMatch.hostPorts, envPrefix).setValue(shell)
        decorateForExternalVolumes(command)
        info.setCommand(command.build)
        builder.setExecutor(info)

        import mesosphere.marathon.api.v2.json.Formats._
        val appJson = Json.toJson(app)
        val appJsonString = Json.stringify(appJson)
        val appJsonByteString = ByteString.copyFromUtf8(appJsonString)
        builder.setData(appJsonByteString)
    }

    // Mesos supports at most one health check, and only COMMAND checks
    // are currently implemented in the Mesos health check helper program.
    val mesosHealthChecks: Set[org.apache.mesos.Protos.HealthCheck] =
      app.healthChecks.collect {
        case healthCheck: HealthCheck if healthCheck.protocol == Protocol.COMMAND => healthCheck.toMesos
      }

    if (mesosHealthChecks.size > 1) {
      val numUnusedChecks = mesosHealthChecks.size - 1
      log.warn(
        "Mesos supports one command health check per task.\n" +
          s"Task [$taskId] will run without " +
          s"$numUnusedChecks of its defined health checks."
      )
    }

    mesosHealthChecks.headOption.foreach(builder.setHealthCheck)

    Some(builder.build -> resourceMatch.hostPorts)
  }

  protected def computeDiscoveryInfo(app: AppDefinition, hostPorts: Seq[Int]): org.apache.mesos.Protos.DiscoveryInfo = {
    val discoveryInfoBuilder = org.apache.mesos.Protos.DiscoveryInfo.newBuilder
    discoveryInfoBuilder.setName(app.id.toHostname)
    discoveryInfoBuilder.setVisibility(org.apache.mesos.Protos.DiscoveryInfo.Visibility.FRAMEWORK)

    val portProtos = app.ipAddress match {
      case Some(IpAddress(_, _, DiscoveryInfo(ports))) if ports.nonEmpty => ports.map(_.toProto)
      case _ =>
        app.portMappings match {
          case Some(portMappings) =>
            // The app uses bridge mode with portMappings, use them to create the Port messages
            portMappings.zip(hostPorts).map {
              case (portMapping, hostPort) => PortMappingSerializer.toMesosPort(portMapping, hostPort)
            }
          case None =>
            // Serialize app.portDefinitions to protos. The port numbers are the service ports, we need to
            // overwrite them the port numbers assigned to this particular task.
            app.portDefinitions.zip(hostPorts).map {
              case (portDefinition, hostPort) =>
                PortDefinitionSerializer.toProto(portDefinition).toBuilder.setNumber(hostPort).build
            }
        }
    }

    val portsProto = org.apache.mesos.Protos.Ports.newBuilder
    portsProto.addAllPorts(portProtos.asJava)
    discoveryInfoBuilder.setPorts(portsProto)

    discoveryInfoBuilder.build
  }

  protected def computeContainerInfo(ports: Seq[Int]): Option[ContainerInfo] = {
    if (app.container.isEmpty && app.ipAddress.isEmpty) None
    else {
      val builder = ContainerInfo.newBuilder

      // Fill in Docker container details if necessary
      app.container.foreach { c =>
        val portMappings = c.docker.map { d =>
          d.portMappings.map { pms =>
            pms zip ports map {
              case (mapping, port) =>
                // Use case: containerPort = 0 and hostPort = 0
                //
                // For apps that have their own service registry and require p2p communication,
                // they will need to advertise
                // the externally visible ports that their components come up on.
                // Since they generally know there container port and advertise that, this is
                // fixed most easily if the container port is the same as the externally visible host
                // port.
                if (mapping.containerPort == 0) {
                  mapping.copy(hostPort = port, containerPort = port)
                }
                else {
                  mapping.copy(hostPort = port)
                }
            }
          }
        }

        val containerWithPortMappings = portMappings match {
          case None => c
          case Some(newMappings) => c.copy(
            docker = c.docker.map {
              _.copy(portMappings = newMappings)
            }
          )
        }
        builder.mergeFrom(ContainerSerializer.toMesos(containerWithPortMappings))
      }

      // Set NetworkInfo if necessary
      app.ipAddress.foreach { ipAddress =>
        val ipAddressLabels = Labels.newBuilder().addAllLabels(ipAddress.labels.map {
          case (key, value) => Label.newBuilder.setKey(key).setValue(value).build()
        }.asJava)
        val networkInfo: NetworkInfo.Builder =
          NetworkInfo.newBuilder()
            .addAllGroups(ipAddress.groups.asJava)
            .setLabels(ipAddressLabels)
            .addIpAddresses(NetworkInfo.IPAddress.getDefaultInstance)
        builder.addNetworkInfos(networkInfo)
      }

      // Set container type to MESOS by default (this is a required field)
      if (!builder.hasType)
        builder.setType(ContainerInfo.Type.MESOS)

      if (builder.getType.equals(ContainerInfo.Type.MESOS)) {
        builder.setMesos(ContainerInfo.MesosInfo.newBuilder()
          .build())
      }
      Some(builder.build)
    }
  }

}

object TaskBuilder {

  val maxEnvironmentVarLength = 512
  val labelEnvironmentKeyPrefix = "MARATHON_APP_LABEL_"
  val maxVariableLength = maxEnvironmentVarLength - labelEnvironmentKeyPrefix.length

  def commandInfo(app: AppDefinition,
                  taskId: Option[Task.Id],
                  host: Option[String],
                  ports: Seq[Int],
                  envPrefix: Option[String]): CommandInfo.Builder = {
    val containerPorts = for (pms <- app.portMappings) yield pms.map(_.containerPort)
    val declaredPorts = containerPorts.getOrElse(app.portNumbers)
    val envMap: Map[String, String] =
      taskContextEnv(app, taskId) ++
        addPrefix(envPrefix, portsEnv(declaredPorts, ports) ++ host.map("HOST" -> _).toMap) ++
        app.env

    val builder = CommandInfo.newBuilder()
      .setEnvironment(environment(envMap))

    app.cmd match {
      case Some(cmd) if cmd.nonEmpty =>
        builder.setValue(cmd)
      case _ =>
        builder.setShell(false)
    }

    // args take precedence over command, if supplied
    app.args.foreach { argv =>
      builder.setShell(false)
      builder.addAllArguments(argv.asJava)
      //mesos command executor expects cmd and arguments
      if (app.container.isEmpty) builder.setValue(argv.head)
    }

    if (app.fetch.nonEmpty) {
      builder.addAllUris(app.fetch.map(_.toProto()).asJava)
    }

    app.user.foreach(builder.setUser)

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

  def portsEnv(definedPorts: Seq[Int], assignedPorts: Seq[Int]): Map[String, String] = {
    if (assignedPorts.isEmpty) {
      Map.empty
    }
    else {
      val env = Map.newBuilder[String, String]

      assignedPorts.zipWithIndex.foreach {
        case (p, n) =>
          env += (s"PORT$n" -> p.toString)
      }

      definedPorts.zip(assignedPorts).foreach {
        case (defined, assigned) =>
          if (defined != AppDefinition.RandomPortValue) {
            env += (s"PORT_$defined" -> assigned.toString)
          }
      }

      env += ("PORT" -> assignedPorts.head.toString)
      env += ("PORTS" -> assignedPorts.mkString(","))
      env.result()
    }
  }

  def addPrefix(envVarsPrefix: Option[String], env: Map[String, String]): Map[String, String] = {
    envVarsPrefix match {
      case Some(prefix) => env.map { case (key: String, value: String) => (prefix + key, value) }
      case None         => env
    }
  }

  def taskContextEnv(app: AppDefinition, taskId: Option[Task.Id]): Map[String, String] = {
    if (taskId.isEmpty) {
      // This branch is taken during serialization. Do not add environment variables in this case.
      Map.empty
    }
    else {
      Seq(
        "MESOS_TASK_ID" -> taskId.map(_.idString),
        "MARATHON_APP_ID" -> Some(app.id.toString),
        "MARATHON_APP_VERSION" -> Some(app.version.toString),
        "MARATHON_APP_DOCKER_IMAGE" -> app.container.flatMap(_.docker.map(_.image)),
        "MARATHON_APP_RESOURCE_CPUS" -> Some(app.cpus.toString),
        "MARATHON_APP_RESOURCE_MEM" -> Some(app.mem.toString),
        "MARATHON_APP_RESOURCE_DISK" -> Some(app.disk.toString)
      ).collect {
          case (key, Some(value)) => key -> value
        }.toMap ++ labelsToEnvVars(app.labels)
    }
  }

  def labelsToEnvVars(labels: Map[String, String]): Map[String, String] = {
    def escape(name: String): String = name.replaceAll("[^a-zA-Z0-9_]+", "_").toUpperCase

    val validLabels = labels.collect {
      case (key, value) if key.length < maxVariableLength
        && value.length < maxEnvironmentVarLength => escape(key) -> value
    }

    val names = Map("MARATHON_APP_LABELS" -> validLabels.keys.mkString(" "))
    val values = validLabels.map { case (key, value) => s"$labelEnvironmentKeyPrefix$key" -> value }
    names ++ values
  }
}
