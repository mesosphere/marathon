package mesosphere.marathon.core.instance.update

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.{ InstanceChanged, MarathonEvent, MesosStatusUpdateEvent }
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp

import scala.collection.immutable.Seq

object InstanceChangedEventsGenerator {
  def events(condition: Condition, instance: Instance, task: Option[Task], now: Timestamp, instanceChanged: Boolean): Seq[MarathonEvent] = {
    val runSpecId = instance.runSpecId
    val version = instance.runSpecVersion

    val instanceEvent: Seq[MarathonEvent] = if (instanceChanged) {
      Seq(InstanceChanged(
        id = instance.instanceId,
        runSpecVersion = version,
        runSpecId = runSpecId,
        condition = condition,
        instance = instance
      ))
    } else Nil

    task.fold(instanceEvent) { task =>
      val maybeTaskStatus = task.status.mesosStatus
      val ports = task.launched.fold(Seq.empty[Int])(_.hostPorts)
      val host = instance.agentInfo.host
      val ipAddresses = maybeTaskStatus.flatMap(status => Task.MesosStatus.ipAddresses(status))
      val slaveId = maybeTaskStatus.fold("")(_.getSlaveId.getValue)
      val message = maybeTaskStatus.fold("")(status => if (status.hasMessage) status.getMessage else "")
      val status = condition.toReadableName

      val taskEvent = MesosStatusUpdateEvent(
        slaveId,
        task.taskId,
        status,
        message,
        appId = runSpecId,
        host,
        ipAddresses,
        ports = ports,
        version = version.toString,
        timestamp = now.toString
      )
      taskEvent +: instanceEvent
    }
  }
}
