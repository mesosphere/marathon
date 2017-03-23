package mesosphere.mesos

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon._
import mesosphere.marathon.api.serialization.ContainerSerializer
import mesosphere.marathon.core.health.MesosHealthCheck
import mesosphere.marathon.core.task
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.plugin.task.RunSpecTaskProcessor
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.ResourceMatcher.ResourceMatch
import mesosphere.mesos.protos.Implicits._
import org.apache.mesos.Protos.Environment._
import org.apache.mesos.Protos._

import scala.collection.immutable.Seq

class TaskBuilder(
    runSpec: AppDefinition,
    newTaskId: PathId => Task.Id,
    config: MarathonConf,
    runSpecTaskProc: RunSpecTaskProcessor = RunSpecTaskProcessor.empty) extends StrictLogging {

  def build(
    offer: Offer,
    resourceMatch: ResourceMatch,
    volumeMatchOpt: Option[PersistentVolumeMatcher.VolumeMatch]): (TaskInfo, task.state.NetworkInfo) = {

    val executor: Executor = if (runSpec.executor == "") {
      config.executor
    } else {
      Executor.dispatch(runSpec.executor)
    }

    val host: Option[String] = Some(offer.getHostname)

    val taskId = newTaskId(runSpec.id)
    val builder = TaskInfo.newBuilder
      // Use a valid hostname to make service discovery easier
      .setName(runSpec.id.toHostname)
      .setTaskId(taskId.mesosTaskId)
      .setSlaveId(offer.getSlaveId)
      .addAllResources(resourceMatch.resources)

    builder.setDiscovery(computeDiscoveryInfo(runSpec, resourceMatch.hostPorts))

    if (runSpec.labels.nonEmpty)
      builder.setLabels(runSpec.labels.toMesosLabels)

    volumeMatchOpt.foreach(_.persistentVolumeResources.foreach(builder.addResources))

    val containerProto = computeContainerInfo(resourceMatch.hostPorts, taskId)
    val envPrefix: Option[String] = config.envVarsPrefix.get

    executor match {
      case CommandExecutor =>
        containerProto.foreach(builder.setContainer)
        val command = TaskBuilder.commandInfo(runSpec, Some(taskId), host, resourceMatch.hostPorts, envPrefix)
        builder.setCommand(command.build)

      case PathExecutor(path) =>
        val executorId = Task.Id.calculateLegacyExecutorId(taskId.idString)
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

    val hostPorts = resourceMatch.hostPorts.flatten
    val networkInfo = task.state.NetworkInfo(offer.getHostname, hostPorts, ipAddresses = Nil)
    val portAssignments = networkInfo.portAssignments(runSpec, includeUnresolved = true)

    // Mesos supports at most one health check
    val mesosHealthChecks =
      runSpec.healthChecks.collect {
        case mesosHealthCheck: MesosHealthCheck =>
          mesosHealthCheck.toMesos(portAssignments)
      }.flatten

    if (mesosHealthChecks.size > 1) {
      val numUnusedChecks = mesosHealthChecks.size - 1
      logger.warn(
        "Mesos supports up to one health check per task.\n" +
          s"Task [$taskId] will run without " +
          s"$numUnusedChecks of its defined health checks."
      )
    }

    mesosHealthChecks.headOption.foreach(builder.setHealthCheck)

    // invoke builder plugins
    runSpecTaskProc.taskInfo(runSpec, builder)
    builder.build -> networkInfo
  }

  protected def computeDiscoveryInfo(
    runSpec: AppDefinition,
    hostPorts: Seq[Option[Int]]): org.apache.mesos.Protos.DiscoveryInfo = {

    val discoveryInfoBuilder = org.apache.mesos.Protos.DiscoveryInfo.newBuilder
    discoveryInfoBuilder.setName(runSpec.id.toHostname)
    discoveryInfoBuilder.setVisibility(org.apache.mesos.Protos.DiscoveryInfo.Visibility.FRAMEWORK)

    val portsProto = org.apache.mesos.Protos.Ports.newBuilder
    portsProto.addAllPorts(PortDiscovery.generate(runSpec, hostPorts))

    discoveryInfoBuilder.setPorts(portsProto)
    discoveryInfoBuilder.build
  }

  protected def computeContainerInfo(hostPorts: Seq[Option[Int]], taskId: Task.Id): Option[ContainerInfo] = {
    if (runSpec.container.isEmpty && !runSpec.networks.hasNonHostNetworking) {
      None
    } else {
      val builder = ContainerInfo.newBuilder

      def boundPortMappings = runSpec.container.withFilter(_.portMappings.nonEmpty).map { c =>
        c.portMappings.zip(hostPorts).collect {
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
      }.getOrElse(Nil)

      // Fill in container details if necessary
      runSpec.container.foreach { c =>
        val containerWithPortMappings = c.copyWith(portMappings = boundPortMappings) match {
          case d: Container.Docker => d.copy(parameters = d.parameters :+
            state.Parameter("label", s"MESOS_TASK_ID=${taskId.mesosTaskId.getValue}")
          )
          case a: Container.MesosAppC => a.copy(labels = a.labels + ("MESOS_TASK_ID" -> taskId.mesosTaskId.getValue))
          case c => c
        }
        builder.mergeFrom(ContainerSerializer.toMesos(runSpec.networks, containerWithPortMappings))
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

  def commandInfo(
    runSpec: AppDefinition,
    taskId: Option[Task.Id],
    host: Option[String],
    hostPorts: Seq[Option[Int]],
    envPrefix: Option[String]): CommandInfo.Builder = {

    val declaredPorts = runSpec.container.withFilter(_.portMappings.nonEmpty).map(
      _.portMappings.map(pm => EnvironmentHelper.PortRequest(pm.name, pm.containerPort))
    ).getOrElse(
        runSpec.portDefinitions.map(pd => EnvironmentHelper.PortRequest(pd.name, pd.port))
      )

    val envMap: Map[String, String] =
      taskContextEnv(runSpec, taskId) ++
        addPrefix(envPrefix, EnvironmentHelper.portsEnv(declaredPorts, hostPorts) ++
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
      builder.addAllArguments(runSpec.args)
      //mesos command executor expects cmd and arguments
      runSpec.args.headOption.foreach { value =>
        if (runSpec.container.isEmpty) builder.setValue(value)
      }
    }

    if (runSpec.fetch.nonEmpty) {
      builder.addAllUris(runSpec.fetch.map(_.toProto))
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

  def taskContextEnv(runSpec: AppDefinition, taskId: Option[Task.Id]): Map[String, String] = {
    if (taskId.isEmpty) {
      // This branch is taken during serialization. Do not add environment variables in this case.
      Map.empty
    } else {
      val envVars: Map[String, String] = Seq(
        "MESOS_TASK_ID" -> taskId.map(_.idString),
        "MARATHON_APP_ID" -> Some(runSpec.id.toString),
        "MARATHON_APP_VERSION" -> Some(runSpec.version.toString),
        "MARATHON_APP_DOCKER_IMAGE" -> runSpec.container.flatMap(_.docker.map(_.image)),
        "MARATHON_APP_RESOURCE_CPUS" -> Some(runSpec.resources.cpus.toString),
        "MARATHON_APP_RESOURCE_MEM" -> Some(runSpec.resources.mem.toString),
        "MARATHON_APP_RESOURCE_DISK" -> Some(runSpec.resources.disk.toString),
        "MARATHON_APP_RESOURCE_GPUS" -> Some(runSpec.resources.gpus.toString)
      ).collect {
          case (key, Some(value)) => key -> value
        }(collection.breakOut)
      envVars ++ EnvironmentHelper.labelsToEnvVars(runSpec.labels)
    }
  }
}
