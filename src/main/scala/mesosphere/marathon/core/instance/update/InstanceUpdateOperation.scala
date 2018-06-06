package mesosphere.marathon
package core.instance.update

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.task.{Task, TaskCondition}
import mesosphere.marathon.state.Timestamp
import org.apache.mesos

import scala.collection.immutable.Seq

sealed trait InstanceUpdateOperation {
  def instanceId: Instance.Id
}

object InstanceUpdateOperation {
  /** Launch (aka create) an ephemeral task*/
  case class LaunchEphemeral(instance: Instance) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = instance.instanceId
  }

  /**
    * Creates a new instance. This is similar to [[LaunchEphemeral]] except that a scheduled instance has no information
    * where it might run.
    *
    * @param reservedInstance The new instance.
    */
  case class RelaunchReserved(reservedInstance: Instance) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = reservedInstance.instanceId
  }

  /** Revert a task to the given state. Used in case TaskOps are rejected. */
  case class Revert(instance: Instance) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = instance.instanceId
  }

  case class Reserve(instance: Instance) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = instance.instanceId
  }

  /**
    * @param instanceId Designating the instance that shall be launched.
    * @param oldToNewTaskIds Mapping from old task IDs to new ones. It is required in order to maintain stable task IDs
    *                        for resident tasks (currently, the ones, which have persistent volumes), which are launched
    *                        upon a resource reservation. Stable here means that only the attempt part changes, and
    *                        the rest stays the same.
    * @param runSpecVersion The runSpec version
    * @param timestamp time
    * @param statuses the tasks' statuses
    * @param hostPorts the assigned hostPorts
    * @param agentInfo The (possibly updated) AgentInfo based on the offer that was used to launch this task, needed to
    *                  keep an Instance's agentInfo updated when re-launching resident tasks. Until Mesos 1.4.0, an
    *                  agent will get a new agentId after a host reboot. Further, in dynamic IP environments, the
    *                  hostname could potentially change after a reboot (especially important to keep up-to-date in the
    *                  context of unique hostname constraints).
    */
  case class LaunchOnReservation(
      instanceId: Instance.Id,
      oldToNewTaskIds: Map[Task.Id, Task.Id],
      runSpecVersion: Timestamp,
      timestamp: Timestamp,
      statuses: Map[Task.Id, Task.Status],
      hostPorts: Map[Task.Id, Seq[Int]],
      agentInfo: AgentInfo) extends InstanceUpdateOperation

  /**
    * Creates a new instance. This is similar to [[LaunchEphemeral]] except that a scheduled instance has no information
    * where it might run.
    *
    * @param instance The new instance.
    */
  case class Schedule(instance: Instance) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = instance.instanceId
  }

  /**
    * Changes the instance state from being scheduled to [[Condition.Provisioned]].
    *
    * Scheduled instance have no agent info (except for reserved instanced). Provisioned instances have such info. They are created when offer is
    * matched.
    *
    * @param instance An instance that has been created during an offer match.
    */
  case class Provision(instance: Instance) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = instance.instanceId
  }

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
  }

  object MesosUpdate {
    def apply(instance: Instance, mesosStatus: mesos.Protos.TaskStatus, now: Timestamp): MesosUpdate = {
      MesosUpdate(instance, TaskCondition(mesosStatus), mesosStatus, now)
    }
  }

  case class ReservationTimeout(instanceId: Instance.Id) extends InstanceUpdateOperation

  /** Expunge a task whose TaskOp was rejected */
  case class ForceExpunge(instanceId: Instance.Id) extends InstanceUpdateOperation
}
