package mesosphere.mesos

import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Environment.Variable
import scala.collection._
import scala.collection.JavaConverters._
import mesosphere.marathon.api.v1.AppDefinition
import org.apache.mesos.Protos.Value.Ranges
import mesosphere.marathon.{TaskQueue, AppResource}
import mesosphere.marathon.AppResource._

/**
 * @author Tobi Knaup
 * @author Shingo Omura
 */

class TaskBuilder(taskQueue: TaskQueue, newTaskId: String => TaskID) {

  def buildTasks(offer:Offer): List[(AppDefinition,TaskInfo)] = {
    TaskBuilder.getPort(offer).map(port => {
      takeTaskIfMatches(offer.asAppResource).map(app => {
        val taskId = newTaskId(app.id)
        app -> TaskInfo.newBuilder
          .setName(taskId.getValue)
          .setTaskId(taskId)
          .setSlaveId(offer.getSlaveId)
          .setCommand(TaskBuilder.commandInfo(app, Some(port)))
          .addResources(TaskBuilder.scalarResource(TaskBuilder.cpusResourceName, app.cpus))
          .addResources(TaskBuilder.scalarResource(TaskBuilder.memResourceName, app.mem))
          .addResources(TaskBuilder.portsResource(port, port))
          .build
      })
    }).getOrElse(List.empty)
  }

  private def takeTaskIfMatches(remainingResource: AppResource): List[AppDefinition] = {
    if (taskQueue.isEmpty()) {
      List.empty
    } else {
      val app = taskQueue.poll()
      if (app.asAppResource.matches(remainingResource)) {
        app :: takeTaskIfMatches(remainingResource.sub(app))
      } else {
        // resource offered was exhausted.
        // Add it back into the queue so the we can try again later.
        // TODO(shingo) can we put this app back to the head of the queue?
        taskQueue.add(app)
        List.empty
      }
    }
  }
}

object TaskBuilder {

  final val cpusResourceName = "cpus"
  final val memResourceName = "mem"
  final val portsResourceName = "ports"

  def portsResource(start: Long, end: Long): Resource = {
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
    if (resource.getRanges.getRangeCount > 0) {
      Some(resource.getRanges.getRange(0).getBegin.toInt)
    } else {
      None
    }
  }
}