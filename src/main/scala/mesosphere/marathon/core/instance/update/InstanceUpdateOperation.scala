package mesosphere.marathon
package core.instance.update

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.task.{ Task, TaskCondition }
import mesosphere.marathon.state.Timestamp
import org.apache.mesos

import scala.collection.immutable.Seq

sealed trait InstanceUpdateOperation {
  def instanceId: Instance.Id
  /**
    * The possible task state if processing the state op succeeds. If processing the
    * state op fails, this state will never be persisted, so be cautious when using it.
    */
  def possibleNewState: Option[Instance] = None
}

object InstanceUpdateOperation {
  /** Launch (aka create) an ephemeral task*/
  case class LaunchEphemeral(instance: Instance) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = instance.instanceId
    override def possibleNewState: Option[Instance] = Some(instance)
  }

  /** Revert a task to the given state. Used in case TaskOps are rejected. */
  case class Revert(instance: Instance) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = instance.instanceId
    override def possibleNewState: Option[Instance] = Some(instance)
  }

  case class Reserve(instance: Instance) extends InstanceUpdateOperation {
    override def instanceId: Instance.Id = instance.instanceId
    override def possibleNewState: Option[Instance] = Some(instance)
  }

  /**
    * @param instanceId Designating the instance that shall be launched.
    * @param newTaskId The id of the task that will be launched via Mesos
    * @param runSpecVersion The runSpec version
    * @param timestamp time
    * @param status
    * @param hostPorts the assigned hostPorts
    * @param agentInfo The (possibly updated) AgentInfo based on the offer that was used to launch this task, needed to
    *                  keep an Instance's agentInfo updated when re-launching resident tasks. Until Mesos 1.4.0, an
    *                  agent will get a new agentId after a host reboot. Further, in dynamic IP environments, the
    *                  hostname could potentially change after a reboot (especially important to keep up-to-date in the
    *                  context of unique hostname constraints).
    */
  case class LaunchOnReservation(
    instanceId: Instance.Id,
    newTaskId: Task.Id,
    runSpecVersion: Timestamp,
    timestamp: Timestamp,
    status: Task.Status, // TODO(PODS): the taskStatus must be created for each task and not passed in here
    hostPorts: Seq[Int],
    agentInfo: AgentInfo) extends InstanceUpdateOperation

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
