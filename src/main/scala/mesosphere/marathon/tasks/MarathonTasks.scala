package mesosphere.marathon.tasks

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Attribute

import scala.collection.JavaConverters._

object MarathonTasks {

  /*
   * Despite its name, stagedAt is set on task creation and before the TASK_STAGED notification from Mesos. This is
   * important because we periodically check for any tasks with an old stagedAt timestamp and kill them (See
   * KillOverdueTasksActor). If stagedAt remains 0 and this check is executed, the task will be killed
   * after being created, given that the check is triggered before we receive a TASK_STAGED notification.
   */
  def makeTask(id: String,
               host: String,
               ports: Iterable[Long],
               attributes: Iterable[Attribute],
               version: Timestamp,
               now: Timestamp,
               slaveId: Protos.SlaveID): MarathonTask = {
    MarathonTask.newBuilder()
      .setId(id)
      .setHost(host)
      .setVersion(version.toString())
      .addAllPorts(ports.map(i => i.toInt: java.lang.Integer).asJava)
      .addAllAttributes(attributes.asJava)
      .setStagedAt(now.toDateTime.getMillis)
      .setSlaveId(slaveId)
      .build
  }

  /**
    * Returns the host address of the supplied task as a string.
    *
    * If the supplied task has at least one NetworkInfo with an IP address
    * filled in, this function returns the first such address.
    *
    * In all other cases, this function returns the slave host address.
    */
  def hostAddress(task: MarathonTask): String = {
    // First, look for an assigned container address.
    val containerAddress: Option[String] =
      if (task.hasNetwork && task.getNetwork.getNetworksList.asScala.nonEmpty) {
        // Take the first element with an IP address for now,
        // since Marathon doesn't know how to deal with multiple addresses.
        task.getNetwork.getNetworksList
          .asScala
          .find(_.hasIpAddress)
          .map(_.getIpAddress)
      }
      else None

    // fall back to the slave host address
    containerAddress getOrElse task.getHost
  }

}
