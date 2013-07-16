package mesosphere.mesos

import org.apache.mesos.Protos._
import org.apache.mesos.Protos.Environment.Variable
import scala.collection._
import scala.collection.JavaConverters._
import mesosphere.marathon.api.v1.AppDefinition
import org.apache.mesos.Protos.Value.Ranges


/**
 * @author Tobi Knaup
 */

class TaskBuilder(app: AppDefinition, newTaskId: String => TaskID) {

  def buildIfMatches(offer: Offer): Option[TaskInfo] = {
    if (!offerMatches(offer)) {
      return None
    }

    getPort(offer).map(port => {
      val taskId = newTaskId(app.id)

      TaskInfo.newBuilder
        .setName(taskId.getValue)
        .setTaskId(taskId)
        .setSlaveId(offer.getSlaveId)
        .setCommand(TaskBuilder.commandInfo(app, Some(port)))
        .addResources(TaskBuilder.scalarResource(TaskBuilder.cpusResourceName, app.cpus))
        .addResources(TaskBuilder.scalarResource(TaskBuilder.memResourceName, app.mem))
        .addResources(portsResource(port, port))
        .build
    })
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

  private def getPort(offeredResource: Resource): Option[Int] = {
    if (offeredResource.getRanges.getRangeCount > 0) {
      Some(offeredResource.getRanges.getRange(0).getBegin.toInt)
    } else {
      None
    }
  }

  private def getPort(offer: Offer): Option[Int] = {
    offer.getResourcesList.asScala
      .find(_.getName == TaskBuilder.portsResourceName)
      .flatMap(getPort)
  }

  private def offerMatches(offer: Offer): Boolean = {
    for (resource <- offer.getResourcesList.asScala) {
      if (resource.getName.eq(TaskBuilder.cpusResourceName) && resource.getScalar.getValue < app.cpus) {
        return false
      }
      if (resource.getName.eq(TaskBuilder.memResourceName) && resource.getScalar.getValue < app.mem) {
        return false
      }
      // TODO handle other resources
    }

    true
  }
}

object TaskBuilder {

  final val cpusResourceName = "cpus"
  final val memResourceName = "mem"
  final val portsResourceName = "ports"

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
}