package mesosphere.mesos

import com.google.protobuf.ByteString
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon._
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.mesos.ResourceMatcher.ResourceMatch
import mesosphere.mesos.protos.{ RangesResource, Resource, ScalarResource }
import org.apache.mesos.Protos.Environment._
import org.apache.mesos.Protos.{ HealthCheck => _, _ }
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilder(app: AppDefinition,
                  newTaskId: PathId => TaskID,
                  config: MarathonConf) {

  import mesosphere.mesos.protos.Implicits._

  val log = LoggerFactory.getLogger(getClass.getName)

  def buildIfMatches(offer: Offer, runningTasks: => Iterable[MarathonTask]): Option[(TaskInfo, Seq[Long])] = {

    val acceptedResourceRoles: Set[String] = app.acceptedResourceRoles.getOrElse(config.defaultAcceptedResourceRolesSet)

    if (log.isDebugEnabled) {
      log.debug(s"acceptedResourceRoles $acceptedResourceRoles")
    }

    ResourceMatcher.matchResources(
      offer, app, runningTasks,
      acceptedResourceRoles = acceptedResourceRoles) match {

        case Some(ResourceMatch(cpu, mem, disk, ranges)) =>
          build(offer, cpu, mem, disk, ranges)

        case _ =>
          def logInsufficientResources(): Unit = {
            val appHostPorts = if (app.requirePorts) app.ports else app.ports.map(_ => 0)
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
                s"mem=${app.mem}, disk=${app.disk}, $portsString, available in offer:\n" + offer
            )
          }

          logInsufficientResources()
          None
      }
  }

  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  private def build(offer: Offer, cpuRole: String, memRole: String, diskRole: String,
                    portsResources: Seq[RangesResource]): Some[(TaskInfo, Seq[Long])] = {

    val executor: Executor = if (app.executor == "") {
      config.executor
    }
    else {
      Executor.dispatch(app.executor)
    }

    val host: Option[String] = Some(offer.getHostname)

    val ports = portsResources.flatMap(_.ranges.flatMap(_.asScala()).to[Seq])

    val labels = app.labels.map {
      case (key, value) =>
        Label.newBuilder.setKey(key).setValue(value).build()
    }

    val taskId = newTaskId(app.id)
    val builder = TaskInfo.newBuilder
      // Use a valid hostname to make service discovery easier
      .setName(app.id.toHostname)
      .setTaskId(taskId)
      .setSlaveId(offer.getSlaveId)
      .addResources(ScalarResource(Resource.CPUS, app.cpus, cpuRole))
      .addResources(ScalarResource(Resource.MEM, app.mem, memRole))

    if (app.disk != 0) {
      // This is only supported since Mesos 0.22.0 and will result in TASK_LOST messages in combination
      // with older mesos versions. So if the user leaves this untouched, we will NOT pass it to
      // Mesos. If the user chooses a value != 0, we assume that they rely on this value and we DO pass it to Mesos
      // irrespective of the version.
      //
      // This is not enforced in Mesos without specifically configuring the appropriate enforcer.
      builder.addResources(ScalarResource(Resource.DISK, app.disk, diskRole))
    }

    if (labels.nonEmpty)
      builder.setLabels(Labels.newBuilder.addAllLabels(labels.asJava))

    portsResources.foreach(builder.addResources(_))

    val containerProto: Option[ContainerInfo] =
      app.container.map { c =>
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
                  mapping.copy(hostPort = port.toInt, containerPort = port.toInt)
                }
                else {
                  mapping.copy(hostPort = port.toInt)
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
        containerWithPortMappings.toMesos()
      }

    val envPrefix: Option[String] = config.envVarsPrefix.get
    executor match {
      case CommandExecutor() =>
        builder.setCommand(TaskBuilder.commandInfo(app, Some(taskId), host, ports, envPrefix))
        containerProto.foreach(builder.setContainer)

      case PathExecutor(path) =>
        val executorId = f"marathon-${taskId.getValue}" // Fresh executor
        val executorPath = s"'$path'" // TODO: Really escape this.
        val cmd = app.cmd orElse app.args.map(_ mkString " ") getOrElse ""
        val shell = s"chmod ug+rx $executorPath && exec $executorPath $cmd"
        val command = TaskBuilder.commandInfo(app, Some(taskId), host, ports, envPrefix).toBuilder.setValue(shell)

        val info = ExecutorInfo.newBuilder()
          .setExecutorId(ExecutorID.newBuilder().setValue(executorId))
          .setCommand(command)
        containerProto.foreach(info.setContainer)
        builder.setExecutor(info)

        import mesosphere.marathon.api.v2.json.Formats._
        val appJson = Json.toJson(V2AppDefinition(app))
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

    Some(builder.build -> ports)
  }

}

object TaskBuilder {

  def commandInfo(app: AppDefinition,
                  taskId: Option[TaskID],
                  host: Option[String],
                  ports: Seq[Long],
                  envPrefix: Option[String]): CommandInfo = {
    val containerPorts = for (pms <- app.portMappings) yield pms.map(_.containerPort)
    val declaredPorts = containerPorts.getOrElse(app.ports)
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

    //scalastyle:off null
    if (app.uris != null) {
      val uriProtos = app.uris.map(uri => {
        CommandInfo.URI.newBuilder()
          .setValue(uri)
          .setExtract(isExtract(uri))
          .build()
      })
      builder.addAllUris(uriProtos.asJava)
    }
    //scalastyle:on

    app.user.foreach(builder.setUser)

    builder.build
  }

  private def isExtract(stringuri: String): Boolean = {
    stringuri.endsWith(".tgz") ||
      stringuri.endsWith(".tar.gz") ||
      stringuri.endsWith(".tbz2") ||
      stringuri.endsWith(".tar.bz2") ||
      stringuri.endsWith(".txz") ||
      stringuri.endsWith(".tar.xz") ||
      stringuri.endsWith(".zip")
  }

  def environment(vars: Map[String, String]): Environment = {
    val builder = Environment.newBuilder()

    for ((key, value) <- vars) {
      val variable = Variable.newBuilder().setName(key).setValue(value)
      builder.addVariables(variable)
    }

    builder.build()
  }

  def portsEnv(definedPorts: Seq[Integer], assignedPorts: Seq[Long]): Map[String, String] = {
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

  def taskContextEnv(app: AppDefinition, taskId: Option[TaskID]): Map[String, String] = {
    if (taskId.isEmpty) {
      // This branch is taken during serialization. Do not add environment variables in this case.
      Map.empty
    }
    else {
      Seq(
        "MESOS_TASK_ID" -> taskId.map(_.getValue),
        "MARATHON_APP_ID" -> Some(app.id.toString),
        "MARATHON_APP_VERSION" -> Some(app.version.toString),
        "MARATHON_APP_DOCKER_IMAGE" -> app.container.flatMap(_.docker.map(_.image))
      ).collect {
          case (key, Some(value)) => key -> value
        }.toMap
    }
  }
}
