package mesosphere.mesos

import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Environment.Variable
import scala.collection._
import scala.collection.JavaConverters._
import mesosphere.marathon.api.v1.AppDefinition
import org.apache.mesos.Protos.Value.Ranges
import org.apache.mesos.Protos
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.tasks.{MarathonTasks, TaskQueue, TaskTracker}
import mesosphere.marathon.AppResource
import mesosphere.marathon.AppResource._

import java.util.logging.Logger
import mesosphere.marathon.{PathExecutor, CommandExecutor, Executor, Main}
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import com.google.protobuf.ByteString


/**
 * @author Tobi Knaup
 * @author Shingo Omura
 */

class TaskBuilder(taskQueue: TaskQueue,
                  taskTracker: TaskTracker,
                  mapper: ObjectMapper = new ObjectMapper()) {

  val log = Logger.getLogger(getClass.getName)

  def buildTasks(offer: Offer): List[(AppDefinition, TaskInfo)] = {
    TaskBuilder.getPort(offer).map(f = port => {
      takeTaskIfMatches(offer.asAppResource, offer).map(app => {

        val executor: Executor = if (app.executor == "") {
          Main.getConfiguration.executor
        } else {
         Executor.dispatch(app.executor)
        }

        val taskId = taskTracker.newTaskId(app.id)

        val marathonTask = MarathonTasks.makeTask(taskId.getValue,
          offer.getHostname, port, offer.getAttributesList.asScala.toList)
        taskTracker.starting(app.id, marathonTask)

        val builder = TaskInfo.newBuilder
          .setName(taskId.getValue)
          .setTaskId(taskId)
          .setSlaveId(offer.getSlaveId)
          .addResources(TaskBuilder.
            scalarResource(TaskBuilder.cpusResourceName, app.cpus))
          .addResources(TaskBuilder.
            scalarResource(TaskBuilder.memResourceName, app.mem))
          .addResources(portsResource(port, port))

        executor match {
          case CommandExecutor() =>
            builder.setCommand(TaskBuilder.commandInfo(app, Some(port)))

          case PathExecutor(path) => {
            val executorId = f"marathon-${taskId.getValue}" // Fresh executor
            val escaped = "'" + path + "'" // TODO: Really escape this.
            val cmd = f"chmod ug+rx $escaped && exec $escaped ${app.cmd}"
            val binary = new ByteArrayOutputStream()
            mapper.writeValue(binary, app)
            val info = ExecutorInfo.newBuilder()
              .setExecutorId(ExecutorID.newBuilder().setValue(executorId))
              .setCommand(CommandInfo.newBuilder().setValue(cmd))
            builder.setExecutor(info)
            builder.setData(ByteString.copyFrom(binary.toByteArray))
          }
        }

        app -> builder.build
      })
    }).getOrElse(List.empty)
  }

  private def portsResource(start: Long, end: Long): Resource = {
    val range = Value.Range.newBuilder
      .setBegin(start)
      .setEnd(end)
      .build
    val ranges = Ranges.newBuilder
      .addRange(range)
      .build
    Resource.newBuilder
      .setName(TaskBuilder.portsResourceName)
      .setType(Value.Type.RANGES)
      .setRanges(ranges)
      .build
  }

  private def takeTaskIfMatches(remainingResource: AppResource, offer: Offer): List[AppDefinition] = {
    if (taskQueue.isEmpty()) {
      List.empty
    } else {
      val app = taskQueue.poll()
      if (app.asAppResource.matches(remainingResource) && meetsConstraints(app, offer)) {
        app :: takeTaskIfMatches(remainingResource.sub(app), offer)
      } else {
        // resource offered was exhausted.
        // Add it back into the queue so the we can try again later.
        // TODO(shingo) can we put this app back to the head of the queue?
        taskQueue.add(app)
        List.empty
      }
    }
  }

  private def meetsConstraints(app: AppDefinition, offer: Offer): Boolean = {
    if (app.constraints.nonEmpty) {
      val currentlyRunningTasks = taskTracker.get(app.id)
      if (app.constraints.filterNot(x =>
        Constraints
          .meetsConstraint(
          currentlyRunningTasks.toSet,
          offer.getAttributesList.asScala.toSet,
          x._1,
          x._2,
          x._3))
        .nonEmpty) {
        log.warning("Did not meet a constraint in an offer." )
        return false
      }
      log.info("Met all constraints.")
    }
    true
  }
}

object TaskBuilder {

  final val cpusResourceName = "cpus"
  final val memResourceName = "mem"
  final val portsResourceName = "ports"
  final val portBlockSize = 5

  def scalarResource(name: String, value: Double) = {
    Resource.newBuilder
      .setName(name)
      .setType(Value.Type.SCALAR)
      .setScalar(Value.Scalar.newBuilder.setValue(value))
      .build
  }

  def commandInfo(app: AppDefinition, portOption: Option[Int]) = {
    val envMap = portOption match {
      case Some(port) => app.env + ("PORT" -> port.toString)
      case None => app.env
    }

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

  def getPort(offer: Offer): Option[Int] = {
    offer.getResourcesList.asScala
      .find(_.getName == portsResourceName)
      .flatMap(getPort)
  }

  def getPort(resource: Resource): Option[Int] = {
    val ranges = resource.getRanges.getRangeList.asScala.map ( range => {
      val portRangeBegin = range.getBegin.toInt + (range.getBegin.toInt % portBlockSize)
      val blocksAvailable = (range.getEnd.toInt - portRangeBegin + 1) / portBlockSize
      (portRangeBegin, blocksAvailable)
    }).filter(range => (range._2 > 0))

    if (ranges.size > 0) {
      val range = util.Random.shuffle(ranges).head
      Some((util.Random.nextInt(range._2) * 5) + range._1)
    } else {
      None
    }
  }
}
