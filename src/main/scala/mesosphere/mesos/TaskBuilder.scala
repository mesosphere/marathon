package mesosphere.mesos

import java.io.ByteArrayOutputStream

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.ByteString
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon._
import mesosphere.marathon.state.{ AppDefinition, PathId }
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.mesos.ResourceMatcher.ResourceMatch
import mesosphere.mesos.protos.{ RangesResource, Resource, ScalarResource }
import org.apache.log4j.Logger
import org.apache.mesos.Protos.Environment._
import org.apache.mesos.Protos._

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilder(app: AppDefinition,
                  newTaskId: PathId => TaskID,
                  taskTracker: TaskTracker,
                  config: MarathonConf,
                  mapper: ObjectMapper = new ObjectMapper()) {

  import mesosphere.mesos.protos.Implicits._

  val log = Logger.getLogger(getClass.getName)

  def buildIfMatches(offer: Offer): Option[(TaskInfo, Seq[Long])] = {

    ResourceMatcher.matchResources(offer, app, taskTracker.get(app.id)) match {
      case Some(ResourceMatch(cpu, mem, disk, ranges)) =>
        build(offer, cpu, mem, disk, ranges)

      case _ =>
        log.info(
          s"No matching offer for ${app.id} (need cpus=${app.cpus}, mem=${app.mem}, " +
            s"disk=${app.disk}, ports=${app.hostPorts()}) : " + offer
        )
        None
    }
  }

  private def build(offer: Offer, cpuRole: String, memRole: String, diskRole: String, portsResource: RangesResource) = {
    val executor: Executor = if (app.executor == "") {
      config.executor
    }
    else {
      Executor.dispatch(app.executor)
    }

    val host: Option[String] = Some(offer.getHostname)

    val ports = portsResource.ranges.flatMap(_.asScala()).to[Seq]

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

    if (labels.nonEmpty)
      builder.setLabels(Labels.newBuilder.addAllLabels(labels.asJava))

    if (portsResource.ranges.nonEmpty)
      builder.addResources(portsResource)

    val containerProto: Option[ContainerInfo] =
      app.container.map { c =>
        val portMappings = c.docker.map { d =>
          d.portMappings.map { pms =>
            pms zip ports map {
              case (mapping, port) => mapping.copy(hostPort = port.toInt)
            }
          }
        }
        val containerWithPortMappings = portMappings match {
          case None => c
          case Some(newMappings) => c.copy(
            docker = c.docker.map { _.copy(portMappings = newMappings) }
          )
        }
        containerWithPortMappings.toMesos
      }

    executor match {
      case CommandExecutor() =>
        builder.setCommand(TaskBuilder.commandInfo(app, Some(taskId), host, ports))
        containerProto.foreach(builder.setContainer)

      case PathExecutor(path) =>
        val executorId = f"marathon-${taskId.getValue}" // Fresh executor
        val executorPath = s"'$path'" // TODO: Really escape this.
        val cmd = app.cmd orElse app.args.map(_ mkString " ") getOrElse ""
        val shell = s"chmod ug+rx $executorPath && exec $executorPath $cmd"
        val command = TaskBuilder.commandInfo(app, Some(taskId), host, ports).toBuilder.setValue(shell)

        val info = ExecutorInfo.newBuilder()
          .setExecutorId(ExecutorID.newBuilder().setValue(executorId))
          .setCommand(command)
        containerProto.foreach(info.setContainer)
        builder.setExecutor(info)
        val binary = new ByteArrayOutputStream()
        mapper.writeValue(binary, app)
        builder.setData(ByteString.copyFrom(binary.toByteArray))
    }

    // Mesos supports at most one health check, and only COMMAND checks
    // are currently implemented in the Mesos health check helper program.
    val mesosHealthChecks: Set[org.apache.mesos.Protos.HealthCheck] =
      app.healthChecks.collect {
        case healthCheck if healthCheck.protocol == Protocol.COMMAND => healthCheck.toMesos
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

  def commandInfo(app: AppDefinition, taskId: Option[TaskID], host: Option[String], ports: Seq[Long]): CommandInfo = {
    val containerPorts = for (pms <- app.portMappings()) yield pms.map(_.containerPort)
    val declaredPorts = containerPorts.getOrElse(app.ports)
    val envMap: Map[String, String] =
      taskContextEnv(app, taskId) ++
        portsEnv(declaredPorts, ports) ++ host.map("HOST" -> _) ++
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
    }

    if (app.uris != null) {
      val uriProtos = app.uris.map(uri => {
        CommandInfo.URI.newBuilder()
          .setValue(uri)
          .setExtract(isExtract(uri))
          .build()
      })
      builder.addAllUris(uriProtos.asJava)
    }

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

  def taskContextEnv(app: AppDefinition, taskId: Option[TaskID]): Map[String, String] =
    if (taskId.isEmpty)
      Map.empty
    else
      Map(
        "MESOS_TASK_ID" -> taskId.get.getValue,
        "MARATHON_APP_ID" -> app.id.toString,
        "MARATHON_APP_VERSION" -> app.version.toString
      )
}
