package mesosphere.marathon.core.task

import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.state.Timestamp
import org.apache.mesos

import scala.collection.immutable.Seq

sealed trait InstanceStateOp {
  def instanceId: Instance.Id
  /**
    * The possible task state if processing the state op succeeds. If processing the
    * state op fails, this state will never be persisted, so be cautious when using it.
    */
  def possibleNewState: Option[Instance] = None
}

object InstanceStateOp {
  /** Launch (aka create) an ephemeral task*/
  // FIXME (3221): The type should be LaunchedEphemeral but that needs a lot of test adjustments
  case class LaunchEphemeral(instance: Instance) extends InstanceStateOp {
    override def instanceId: Instance.Id = instance.instanceId
    override def possibleNewState: Option[Instance] = Some(instance)
  }

  /** Revert a task to the given state. Used in case TaskOps are rejected. */
  case class Revert(instance: Instance) extends InstanceStateOp {
    override def instanceId: Instance.Id = instance.instanceId
    override def possibleNewState: Option[Instance] = Some(instance)
  }

  case class Reserve(task: Task.Reserved) extends InstanceStateOp {
    override def instanceId: Instance.Id = task.taskId.instanceId
    override def possibleNewState: Option[Instance] = Some(Instance(task))
  }

  case class LaunchOnReservation(
    instanceId: Instance.Id,
    runSpecVersion: Timestamp,
    status: Task.Status,
    hostPorts: Seq[Int]) extends InstanceStateOp

  case class MesosUpdate(task: Task, status: InstanceStatus,
      mesosStatus: mesos.Protos.TaskStatus, now: Timestamp) extends InstanceStateOp {
    override def instanceId: Instance.Id = task.taskId.instanceId
  }

  object MesosUpdate {
    def apply(task: Task, mesosStatus: mesos.Protos.TaskStatus, now: Timestamp): MesosUpdate = {
      MesosUpdate(task, MarathonTaskStatus(mesosStatus), mesosStatus, now)
    }
  }

  case class ReservationTimeout(instanceId: Instance.Id) extends InstanceStateOp

  /** Expunge a task whose TaskOp was rejected */
  case class ForceExpunge(instanceId: Instance.Id) extends InstanceStateOp
}
