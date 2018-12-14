package mesosphere.marathon
package core.instance.update

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Goal
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.{Task, TaskCondition}
import mesosphere.marathon.state.{RunSpec, Timestamp}
import org.apache.mesos

sealed trait InstanceUpdateOperation {
  def instanceId: Instance.Id

  def shortString: String = s"${this.getClass.getSimpleName} instance update operation for $instanceId"
}

object InstanceUpdateOperation {
  /** Revert a task to the given state. Used in case TaskOps are rejected. */
  case class Revert(instance: Instance) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = instance.instanceId
  }

  /**
    * * Reschedules resident instance that already has a reservation but became terminal (reserved).
    *
    * @param reservedInstance already existing reserved instance that is now in scheduled state.
    */
  case class RescheduleReserved(reservedInstance: Instance, runSpec: RunSpec) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = reservedInstance.instanceId
  }

  case class Reserve(instance: Instance) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = instance.instanceId
  }

  /**
    * Creates a new instance. Scheduled instance has no information where it might run.
    *
    * @param instance The new instance.
    */
  case class Schedule(instance: Instance) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = instance.instanceId
  }

  /**
    * Changes the instance state from being scheduled to [[Condition.Provisioned]].
    *
    * Scheduled instance have no agent info. Provisioned instances have such info. They are created when offer is
    * matched.
    *
    */
  case class Provision(instanceId: Instance.Id, agentInfo: Instance.AgentInfo, runSpec: RunSpec, tasks: Map[Task.Id, Task], now: Timestamp) extends InstanceUpdateOperation

  /**
    * Describes an instance update.
    *
    * @param instance Instance that is updated
    * @param condition New Condition of instance
    * @param mesosStatus New Mesos status
    * @param now Time when update was received
    */
  case class MesosUpdate(
      instance: Instance, condition: Condition,
      mesosStatus: mesos.Protos.TaskStatus, now: Timestamp) extends InstanceUpdateOperation {

    override def instanceId: Instance.Id = instance.instanceId

    override def shortString: String = s"${this.getClass.getSimpleName} update operation for $instanceId with new status ${mesosStatus.getState}"
  }

  object MesosUpdate {
    def apply(instance: Instance, mesosStatus: mesos.Protos.TaskStatus, now: Timestamp): MesosUpdate = {
      MesosUpdate(instance, TaskCondition(mesosStatus), mesosStatus, now)
    }
  }

  case class ReservationTimeout(instanceId: Instance.Id) extends InstanceUpdateOperation

  case class ChangeGoal(instanceId: Instance.Id, goal: Goal) extends InstanceUpdateOperation {
    override def shortString: String = s"${this.getClass.getSimpleName} instance update operation for $instanceId with new goal $goal"
  }

  /** Expunge a task whose TaskOp was rejected */
  case class ForceExpunge(instanceId: Instance.Id) extends InstanceUpdateOperation
}
