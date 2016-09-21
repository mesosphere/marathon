package mesosphere.marathon

import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos

import scala.collection.immutable.Seq

case class InstanceBuilder(
    instance: Instance, now: Timestamp = Timestamp.now()
) {

  def addLaunchedTask(container: Option[MesosContainer] = None) = {
    val task = MarathonTestHelper.minimalTask(instance.instanceId, container)
    this.copy(instance = instance.copy(tasksMap = instance.tasksMap + (task.taskId -> task)))
  }

  def pickFirstTask(): Task = instance.tasks.headOption.getOrElse(throw new RuntimeException("no task in instance"))

  def getInstance() = instance

  def stateOpLaunch() = InstanceUpdateOperation.LaunchEphemeral(instance)
  def stateOpUpdate(mesosStatus: mesos.Protos.TaskStatus, now: Timestamp = now) = InstanceUpdateOperation.MesosUpdate(instance, mesosStatus, now)
  def stateOpExpunge() = InstanceUpdateOperation.ForceExpunge(instance.instanceId)
  def stateOpLaunchOnReservation(status: Task.Status) = InstanceUpdateOperation.LaunchOnReservation(instance.instanceId, instance.runSpecVersion, now, status, Seq.empty)
  def stateOpReservationTimeout() = InstanceUpdateOperation.ReservationTimeout(instance.instanceId)
  def stateOpReserve(instance: Instance) = InstanceUpdateOperation.Reserve(instance)
}

object InstanceBuilder {

  private def emptyInstance(runSpecId: PathId): Instance = Instance(
    instanceId = Instance.Id.forRunSpec(runSpecId),
    agentInfo = InstanceBuilder.defaultAgentInfo,
    state = InstanceState(InstanceStatus.Created, Timestamp.now(), Timestamp.now(), healthy = None),
    tasksMap = Map.empty
  )

  private val defaultAgentInfo = Instance.AgentInfo(host = "host.some", agentId = None, attributes = Seq.empty)

  def newBuilder(runSpecId: PathId): InstanceBuilder = InstanceBuilder(emptyInstance(runSpecId))

  def newBuilderWithLaunchedTask(runSpecId: PathId): InstanceBuilder = newBuilder(runSpecId).addLaunchedTask()
}
