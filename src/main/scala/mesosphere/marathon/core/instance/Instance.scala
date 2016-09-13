package mesosphere.marathon.core.instance

import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.update.InstanceUpdateEffect
import mesosphere.marathon.core.task.update.TaskUpdateOperation
import mesosphere.marathon.core.task.update.TaskUpdateEffect
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache._

case class Instance(
    instanceId: Instance.Id,
    agentInfo: Instance.AgentInfo,
    state: InstanceState,
    tasksMap: Map[Task.Id, Task]) {

  // TODO(PODS): check consumers of this def and see if they can use the map instead
  val tasks = tasksMap.values

  val runSpecVersion: Timestamp = state.version
  val runSpecId: PathId = instanceId.runSpecId
  val isLaunched: Boolean = tasks.forall(task => task.launched.isDefined)

  // TODO(PODS): verify functionality and reduce complexity
  // scalastyle:off cyclomatic.complexity
  def update(op: InstanceUpdateOperation): InstanceUpdateEffect = {
    // TODO(PODS): implement logic:
    // - propagate the change to the task
    // - calculate the new instance status based on the state of the task

    // TODO(PODS): make sure state transitions are allowed. maybe implement a simple state machine?
    op match {
      case InstanceUpdateOperation.ForceExpunge(_) =>
        InstanceUpdateEffect.Expunge(this)

      case InstanceUpdateOperation.MesosUpdate(instance, status, mesosStatus, now) =>
        // TODO(PODS): calculate the overall state afterwards
        val taskId = Task.Id(mesosStatus.getTaskId)
        val effect = tasks.find(_.taskId == taskId).map { task =>
          task.update(TaskUpdateOperation.MesosUpdate(mesosStatus))
        }.getOrElse(TaskUpdateEffect.Failure(s"$taskId not found in $instanceId"))

        effect match {
          case TaskUpdateEffect.Update(newTaskState) =>
            val updated: Instance = copy(tasksMap = tasksMap.updated(newTaskState.taskId, newTaskState))
            InstanceUpdateEffect.Update(updated, Some(this))

          case TaskUpdateEffect.Expunge(oldState) =>
            InstanceUpdateEffect.Expunge(this)

          case TaskUpdateEffect.Noop =>
            InstanceUpdateEffect.Noop(instance.instanceId)

          case TaskUpdateEffect.Failure(cause) =>
            InstanceUpdateEffect.Failure(cause)
        }

      case InstanceUpdateOperation.LaunchOnReservation(_, version, status, hostPorts) =>
        if (this.isReserved) {
          val updated: Instance = ???
          InstanceUpdateEffect.Update(updated, Some(this))
        } else {
          InstanceUpdateEffect.Failure("LaunchOnReservation can only be applied to a reserved instance")
        }

      case InstanceUpdateOperation.ReservationTimeout(_) =>
        if (this.isReserved) {
          InstanceUpdateEffect.Expunge(this)
        } else {
          InstanceUpdateEffect.Failure("LaunchOnReservation can only be applied to a reserved instance")
        }

      case InstanceUpdateOperation.LaunchEphemeral(instance) =>
        InstanceUpdateEffect.Failure("LaunchEphemeral cannot be passed to an existing instance")

      case InstanceUpdateOperation.Reserve(_) =>
        InstanceUpdateEffect.Failure("LaunchEphemeral cannot be passed to an existing instance")

      case InstanceUpdateOperation.Revert(oldState) =>
        InstanceUpdateEffect.Failure("LaunchEphemeral cannot be passed to an existing instance")
    }
  }
  // scalastyle:on
}

object Instance {

  def instancesById(tasks: Iterable[Instance]): Map[Instance.Id, Instance] =
    tasks.iterator.map(task => task.instanceId -> task).toMap

  // TODO ju remove apply
  def apply(task: Task): Instance = new Instance(Id(task.taskId), task.agentInfo,
    InstanceState(task.status.taskStatus, task.status.startedAt.getOrElse(task.status.stagedAt),
      task.version.getOrElse(Timestamp.zero)), Map(task.taskId -> task))

  case class InstanceState(status: InstanceStatus, since: Timestamp, version: Timestamp)

  case class Id(idString: String) extends Ordered[Id] {
    lazy val runSpecId: PathId = Id.runSpecId(idString)
    // TODO(jdef) move this somewhere else?
    lazy val mesosExecutorId: mesos.Protos.ExecutorID = mesos.Protos.ExecutorID.newBuilder().setValue(idString).build()

    override def toString: String = s"instance [$idString]"

    override def compare(that: Instance.Id): Int =
      if (this.getClass == that.getClass)
        idString.compare(that.idString)
      else this.compareTo(that)
  }

  object Id {
    private val InstanceIdRegex = """^(.+)[\._]([^_\.]+)$""".r

    def apply(executorId: mesos.Protos.ExecutorID): Id = new Id(executorId.getValue)

    def apply(taskId: Task.Id): Id = new Id(taskId.idString) // TODO PODs replace with proper calculation

    def runSpecId(instanceId: String): PathId = { // TODO PODs is this calculated correct?
      instanceId match {
        case InstanceIdRegex(runSpecId, uuid) => PathId.fromSafePath(runSpecId)
      }
    }

    def forRunSpec(id: PathId): Id = Task.Id.forRunSpec(id).instanceId
  }

  /**
    * Info relating to the host on which the Instance has been launched.
    */
  case class AgentInfo(
    host: String,
    agentId: Option[String],
    attributes: Iterable[mesos.Protos.Attribute])

  implicit class InstanceStatusComparison(val instance: Instance) extends AnyVal {
    def isReserved: Boolean = instance.state.status == InstanceStatus.Reserved
    def isCreated: Boolean = instance.state.status == InstanceStatus.Created
    def isError: Boolean = instance.state.status == InstanceStatus.Error
    def isFailed: Boolean = instance.state.status == InstanceStatus.Failed
    def isFinished: Boolean = instance.state.status == InstanceStatus.Finished
    def isKilled: Boolean = instance.state.status == InstanceStatus.Killed
    def isKilling: Boolean = instance.state.status == InstanceStatus.Killing
    def isRunning: Boolean = instance.state.status == InstanceStatus.Running
    def isStaging: Boolean = instance.state.status == InstanceStatus.Staging
    def isStarting: Boolean = instance.state.status == InstanceStatus.Starting
    def isUnreachable: Boolean = instance.state.status == InstanceStatus.Unreachable
    def isGone: Boolean = instance.state.status == InstanceStatus.Gone
    def isUnknown: Boolean = instance.state.status == InstanceStatus.Unknown
    def isDropped: Boolean = instance.state.status == InstanceStatus.Dropped
  }

  /**
    * Marathon has requested (or will request) that this instance be launched by Mesos.
    * @param instance is the thing that Marathon wants to launch
    * @param hostPorts is a list of actual (no dynamic!) hort-ports that are being requested from Mesos.
    */
  case class LaunchRequest(
    instance: Instance,
    hostPorts: Seq[Int])
}
