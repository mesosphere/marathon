package mesosphere.mesos

import java.util.logging.Logger
import java.io.ByteArrayOutputStream
import scala.collection._
import scala.collection.JavaConverters._
import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Environment._
import org.apache.mesos.Protos.Value.Ranges
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon._
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.ByteString
import scala.util.Random
import mesosphere.marathon.Protos.Constraint.Operator
import scala.Some


/**
 * @author Tobi Knaup
 */

class TaskBuilder (app: AppDefinition,
                   newTaskId: String => TaskID,
                   taskTracker: TaskTracker,
                   mapper: ObjectMapper = new ObjectMapper()) {

  val log = Logger.getLogger(getClass.getName)

  def buildIfMatches(offer: Offer): Option[(TaskInfo, Seq[Int])] = {
    var cpuRole = ""
    var memRole = ""

    offerMatches(offer) match {
      case Some((cpu, mem)) =>
        cpuRole = cpu
        memRole = mem
      case _ =>
        log.info(s"No matching offer (need ${app.cpus} CPUs, ${app.mem} mem, ${app.ports.size} ports) : " + offer)
        return None
    }

    val executor: Executor = if (app.executor == "") {
      Main.conf.executor
    } else {
      Executor.dispatch(app.executor)
    }

    TaskBuilder.getPorts(offer, app.ports.size).map { portRanges =>
      val ports = portRanges.flatMap { case (begin, end, _) => begin to end }

      val taskId = newTaskId(app.id)
      val builder = TaskInfo.newBuilder
        .setName(taskId.getValue)
        .setTaskId(taskId)
        .setSlaveId(offer.getSlaveId)
        .addResources(TaskBuilder.
          scalarResource(TaskBuilder.cpusResourceName, app.cpus, cpuRole))
        .addResources(TaskBuilder.
          scalarResource(TaskBuilder.memResourceName, app.mem, memRole))

      if (portRanges.nonEmpty) {
        portRanges.foreach(r => builder.addResources(portsResource(r)))
      }

      executor match {
        case CommandExecutor() => {
          if (app.container.nonEmpty) {
            log.warning("The command executor can not handle container " +
                        "options. No tasks will be started for this " +
                        "appliction.")
            return None
          }
          builder.setCommand(TaskBuilder.commandInfo(app, ports))
        }

        case PathExecutor(path) => {
          val executorId = f"marathon-${taskId.getValue}" // Fresh executor
          val escaped = "'" + path + "'" // TODO: Really escape this.
          val shell = f"chmod ug+rx $escaped && exec $escaped ${app.cmd}"
          val command =
            TaskBuilder.commandInfo(app, ports).toBuilder.setValue(shell)

          val info = ExecutorInfo.newBuilder()
            .setExecutorId(ExecutorID.newBuilder().setValue(executorId))
            .setCommand(command)

          builder.setExecutor(info)
          val binary = new ByteArrayOutputStream()
          mapper.writeValue(binary, app)
          builder.setData(ByteString.copyFrom(binary.toByteArray))
        }
      }

      builder.build -> ports
    }
  }

  private def portsResource(range: (Int, Int, String)): Resource = {
    val rangeProtos = Value.Range.newBuilder
      .setBegin(range._1)
      .setEnd(range._2)
      .build

    val rangesProto = Ranges.newBuilder
      .addRange(rangeProtos)
      .build
    Resource.newBuilder
      .setName(TaskBuilder.portsResourceName)
      .setType(Value.Type.RANGES)
      .setRanges(rangesProto)
      .setRole(range._3)
      .build
  }

  private def offerMatches(offer: Offer): Option[(String, String)] = {
    var cpuRole = ""
    var memRole = ""

    for (resource <- offer.getResourcesList.asScala) {
      if (cpuRole.isEmpty &&
        resource.getName == TaskBuilder.cpusResourceName &&
        resource.getScalar.getValue >= app.cpus) {
        cpuRole = resource.getRole
      }
      if (memRole.isEmpty &&
        resource.getName == TaskBuilder.memResourceName &&
        resource.getScalar.getValue >= app.mem) {
        memRole = resource.getRole
      }
      // TODO handle other resources
    }

    if (cpuRole.isEmpty || memRole.isEmpty) {
      return None
    }

    if (app.constraints.nonEmpty) {
      val runningTasks = taskTracker.get(app.id)
      val constraintsMet = app.constraints.forall(
        Constraints.meetsConstraint(runningTasks, offer, _)
      )
      if (!constraintsMet) {
        log.warning("Did not meet a constraint in an offer." )
        return None
      }
      log.info("Met all constraints.")
    }
    Some((cpuRole, memRole))
  }
}

object TaskBuilder {

  final val cpusResourceName = "cpus"
  final val memResourceName = "mem"
  final val portsResourceName = "ports"

  def scalarResource(name: String, value: Double, role: String) = {
    Resource.newBuilder
      .setName(name)
      .setType(Value.Type.SCALAR)
      .setScalar(Value.Scalar.newBuilder.setValue(value))
      .setRole(role)
      .build
  }

  def scalarResource(name: String, value: Double): Resource = {
    // Added for convenience.  Uses default catch-all role.
    scalarResource(name, value, "*")
  }

  def commandInfo(app: AppDefinition, ports: Seq[Int]) = {
    val envMap = app.env ++ portsEnv(ports)

    val builder = CommandInfo.newBuilder()
      .setValue(app.cmd)
      .setEnvironment(environment(envMap))

    if (app.uris != null) {
      val uriProtos = app.uris.map(uri => {
        CommandInfo.URI.newBuilder()
          .setValue(uri)
          .build()
      })
      builder.addAllUris(uriProtos.asJava)
    }

    builder.build
  }

  def environment(vars: Map[String, String]) = {
    val builder = Environment.newBuilder()

    for ((key, value) <- vars) {
      val variable = Variable.newBuilder().setName(key).setValue(value)
      builder.addVariables(variable)
    }

    builder.build()
  }

  def getPorts(offer: Offer, numPorts: Int): Option[Seq[(Int, Int, String)]] = {
    offer.getResourcesList.asScala
      .find(_.getName == portsResourceName)
      .flatMap(getPorts(_, numPorts))
  }

  def getPorts(resource: Resource, numPorts: Int): Option[Seq[(Int, Int, String)]] = {
    if (numPorts == 0) {
      return Some(Seq())
    }

    val ranges = Random.shuffle(resource.getRanges.getRangeList.asScala)
    for (range <- ranges) {
      // TODO use multiple ranges if one is not enough
      if (range.getEnd - range.getBegin + 1 >= numPorts) {
        val maxOffset = (range.getEnd - range.getBegin - numPorts + 2).toInt
        val firstPort = range.getBegin.toInt + Random.nextInt(maxOffset)
        return Some(Seq((firstPort, firstPort + numPorts - 1, resource.getRole)))
      }
    }
    None
  }

  def portsEnv(ports: Seq[Int]): Map[String, String] = {
    if (ports.isEmpty) {
      return Map.empty
    }

    val env = mutable.HashMap.empty[String, String]

    ports.zipWithIndex.foreach(p => {
      env += (s"PORT${p._2}" -> p._1.toString)
    })

    env += ("PORT" -> ports.head.toString)
    env += ("PORTS" -> ports.mkString(","))
    env
  }
}
