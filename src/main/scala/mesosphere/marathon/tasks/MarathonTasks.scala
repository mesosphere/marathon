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

  def ipAddresses(task: MarathonTask): Seq[Protos.NetworkInfo.IPAddress] = {
    task.getNetworksList.asScala.flatMap(_.getIpAddressesList.asScala.toList)
  }

  /**
    * Returns the IP address (as string) to use connect to the task.
    *
    * If the supplied task has at least one NetworkInfo with an IP address
    * filled in, this function returns the first such address.
    *
    * In all other cases, this function returns the slave host address.
    */
  def effectiveIpAddress(task: MarathonTask): String = {
    ipAddresses(task).map(_.getIpAddress).headOption.getOrElse(task.getHost)
  }
}
