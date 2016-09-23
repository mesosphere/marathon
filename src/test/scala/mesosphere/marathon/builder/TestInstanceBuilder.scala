package mesosphere.marathon.builder

import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.InstanceUpdateOperation
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos
import scala.concurrent.duration._

import scala.collection.immutable.Seq

case class TestInstanceBuilder(
    instance: Instance, now: Timestamp = Timestamp.now()
) {

  def addTaskLaunched(container: Option[MesosContainer] = None): TestInstanceBuilder =
    addTaskWithBuilder().taskLaunched(container).build()

  def addTaskReserved(reservation: Task.Reservation = TestTaskBuilder.Creator.newReservation): TestInstanceBuilder =
    addTaskWithBuilder().taskReserved(reservation).build()

  def addTaskWithBuilder(): TestTaskBuilder = TestTaskBuilder.newBuilder(this)

  private[builder] def addTask(task: Task): TestInstanceBuilder =
    this.copy(instance = instance.copy(tasksMap = instance.tasksMap + (task.taskId -> task)), now = now + 1.second)

  def pickFirstTask(): Task = instance.tasks.headOption.getOrElse(throw new RuntimeException("no task in instance"))

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

  def emptyInstance(runSpecId: PathId): Instance = Instance(
    instanceId = Instance.Id.forRunSpec(runSpecId),
    agentInfo = TestInstanceBuilder.defaultAgentInfo,
    state = InstanceState(InstanceStatus.Created, Timestamp.now(), Timestamp.now(), healthy = None),
    tasksMap = Map.empty
  )

  private val defaultAgentInfo = Instance.AgentInfo(host = "host.some", agentId = None, attributes = Seq.empty)

  def newBuilder(runSpecId: PathId): TestInstanceBuilder = TestInstanceBuilder(emptyInstance(runSpecId))

  def newBuilderWithLaunchedTask(runSpecId: PathId): TestInstanceBuilder = newBuilder(runSpecId).addTaskLaunched()
}
