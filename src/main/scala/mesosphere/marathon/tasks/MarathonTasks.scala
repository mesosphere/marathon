package mesosphere.marathon.tasks

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Attribute

import scala.collection.JavaConverters._

// FIXME(PK): Remove
object MarathonTasks {

  // FIXME(PK): Move to Task
  def ipAddresses(task: MarathonTask): Seq[Protos.NetworkInfo.IPAddress] = {
    task.getNetworksList.asScala.flatMap(_.getIpAddressesList.asScala.toList)
  }

  /**
    * Returns the IP address (as string) to use connect to the task.
    *
    * If the supplied app has a non-empty IpAddress and the task has at least one NetworkInfo with an IP address filled
    * in, this function returns the first such address.
    *
    * In all other cases, this function returns the slave host address.
    */
  // FIXME(PK): Move to Task
  def effectiveIpAddress(app: AppDefinition, task: MarathonTask): String = {
    val maybeContainerIp: Option[String] = ipAddresses(task).map(_.getIpAddress).headOption

    maybeContainerIp match {
      case Some(ipAddress) if app.ipAddress.isDefined =>
        ipAddress
      case _ =>
        task.getHost
    }
  }

  // FIXME(PK): Move to Task
  def taskMap(tasks: Iterable[MarathonTask]): Map[String, MarathonTask] = tasks.map(task => task.getId -> task).toMap
}
