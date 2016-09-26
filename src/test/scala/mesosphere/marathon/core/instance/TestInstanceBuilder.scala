package mesosphere.marathon.core.instance

import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos

import scala.collection.immutable.Seq
import scala.concurrent.duration._

case class TestInstanceBuilder(
    instance: Instance, now: Timestamp = Timestamp.now()
) {

  def addTaskLaunched(container: Option[MesosContainer] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskLaunched(container).build()

  def addTaskReserved(reservation: Task.Reservation = TestTaskBuilder.Creator.newReservation): TestInstanceBuilder =
    addTaskWithBuilder().taskReserved(reservation).build()

  def addTaskResidentReserved(localVolumeIds: Task.LocalVolumeId*): TestInstanceBuilder =
    addTaskWithBuilder().taskResidentReserved(localVolumeIds: _*).build()

  def addTaskResidentLaunched(localVolumeIds: Task.LocalVolumeId*): TestInstanceBuilder =
    addTaskWithBuilder().taskResidentLaunched(localVolumeIds: _*).build()

  def addTaskRunning(container: Option[MesosContainer] = None, stagedAt: Timestamp = now, startedAt: Timestamp = now): TestInstanceBuilder =
    addTaskWithBuilder().taskRunning(container, stagedAt, startedAt).build()

  def addTaskStaged(stagedAt: Timestamp = now): TestInstanceBuilder =
    addTaskWithBuilder().taskStaged(stagedAt).build()

  def addTaskWithBuilder(): TestTaskBuilder = TestTaskBuilder.newBuilder(this)

  private[instance] def addTask(task: Task): TestInstanceBuilder =
    this.copy(instance = instance.updatedInstance(task, now + 1.second))

  def pickFirstTask[T <: Task](): T = instance.tasks.headOption.getOrElse(throw new RuntimeException("No matching Task in Instance")).asInstanceOf[T]

  def getInstance() = instance

  def stateOpLaunch() = InstanceUpdateOperation.LaunchEphemeral(instance)

  def stateOpUpdate(mesosStatus: mesos.Protos.TaskStatus) = InstanceUpdateOperation.MesosUpdate(instance, mesosStatus, now)

  def taskLaunchedOp(): InstanceUpdateOperation.LaunchOnReservation = {
    InstanceUpdateOperation.LaunchOnReservation(instanceId = instance.instanceId, timestamp = now, runSpecVersion = instance.runSpecVersion, status = Task.Status(stagedAt = now, taskStatus = InstanceStatus.Running), hostPorts = Seq.empty)
  }

  def stateOpExpunge() = InstanceUpdateOperation.ForceExpunge(instance.instanceId)

  def stateOpReservationTimeout() = InstanceUpdateOperation.ReservationTimeout(instance.instanceId)
}

object TestInstanceBuilder {

  def emptyInstance(runSpecId: PathId, now: Timestamp = Timestamp.now(), version: Timestamp = Timestamp.zero): Instance = Instance(
    instanceId = Instance.Id.forRunSpec(runSpecId),
    agentInfo = TestInstanceBuilder.defaultAgentInfo,
    state = InstanceState(InstanceStatus.Created, now, version, healthy = None),
    tasksMap = Map.empty
  )

  private val defaultAgentInfo = Instance.AgentInfo(host = "host.some", agentId = None, attributes = Seq.empty)

  def newBuilder(runSpecId: PathId, now: Timestamp = Timestamp.now()): TestInstanceBuilder = TestInstanceBuilder(emptyInstance(runSpecId, now), now)

  def newBuilderWithLaunchedTask(runSpecId: PathId, now: Timestamp = Timestamp.now()): TestInstanceBuilder = newBuilder(runSpecId, now).addTaskLaunched()
}
