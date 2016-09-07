package mesosphere.marathon.core.task

import mesosphere.marathon.core.instance.{ Instance, InstanceStateOp, InstanceStatus }
import mesosphere.marathon.state.Timestamp
import org.apache.mesos

import scala.collection.immutable.Seq

sealed trait TaskStateOp extends InstanceStateOp

object TaskStateOp {
  /** Launch (aka create) an ephemeral task*/
  // FIXME (3221): The type should be LaunchedEphemeral but that needs a lot of test adjustments
  case class LaunchEphemeral(task: Task) extends TaskStateOp {
    override def instanceId: Instance.Id = task.id
    override def possibleNewState: Option[Instance] = Some(task)
  }

  /** Revert a task to the given state. Used in case TaskOps are rejected. */
  case class Revert(task: Task) extends TaskStateOp {
    override def instanceId: Instance.Id = task.id
    override def possibleNewState: Option[Instance] = Some(task)
  }

  case class Reserve(task: Task.Reserved) extends TaskStateOp {
    override def instanceId: Instance.Id = task.id
    override def possibleNewState: Option[Instance] = Some(task)
  }

  case class LaunchOnReservation(
    instanceId: Instance.Id,
    runSpecVersion: Timestamp,
    status: Task.Status,
    hostPorts: Seq[Int]) extends TaskStateOp

  case class MesosUpdate(task: Task, status: InstanceStatus,
      mesosStatus: mesos.Protos.TaskStatus, now: Timestamp) extends TaskStateOp {
    override def instanceId: Instance.Id = task.id
  }

  object MesosUpdate {
    def apply(task: Task, mesosStatus: mesos.Protos.TaskStatus, now: Timestamp): MesosUpdate = {
      MesosUpdate(task, MarathonTaskStatus(mesosStatus), mesosStatus, now)
    }
  }

  case class ReservationTimeout(instanceId: Instance.Id) extends TaskStateOp
}
