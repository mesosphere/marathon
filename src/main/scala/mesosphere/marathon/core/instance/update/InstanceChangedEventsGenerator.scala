package mesosphere.marathon.core.instance.update

import mesosphere.marathon.core.event.{ InstanceChanged, MarathonEvent, MesosStatusUpdateEvent }
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp

import scala.collection.immutable.Seq

object InstanceChangedEventsGenerator {
  def events(status: InstanceStatus, instance: Instance, task: Option[Task], now: Timestamp): Seq[MarathonEvent] = {
    val runSpecId = instance.runSpecId
    val version = instance.runSpecVersion

    def instanceEvent = InstanceChanged(
      id = instance.instanceId,
      runSpecVersion = version,
      runSpecId = runSpecId,
      status = status,
      instance = instance
    )

    task.fold(Seq[MarathonEvent](instanceEvent)) { task =>
      val maybeTaskStatus = task.status.mesosStatus
      val ports = task.launched.fold(Seq.empty[Int])(_.hostPorts)
      val host = instance.agentInfo.host
      val ipAddresses = maybeTaskStatus.flatMap(status => Task.MesosStatus.ipAddresses(status))
      val slaveId = maybeTaskStatus.fold("")(_.getSlaveId.getValue)
      val message = maybeTaskStatus.fold("")(status => if (status.hasMessage) status.getMessage else "")
      val taskEvent = MesosStatusUpdateEvent(
        slaveId,
        task.taskId,
        status.toMesosStateName,
        message,
        appId = runSpecId,
        host,
        ipAddresses,
        ports = ports,
        version = version.toString,
        timestamp = now.toString
      )
      Seq[MarathonEvent](instanceEvent, taskEvent)
    }
  }
}
