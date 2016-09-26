package mesosphere.marathon.core.instance

import mesosphere.marathon.test.MarathonTestHelper.Implicits._
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.update.TaskUpdateOperation
import mesosphere.marathon.core.task.{ MarathonTaskStatus, Task }
import mesosphere.marathon.state.{ PathId, Timestamp }
import org.apache.mesos.Protos._

import scala.collection.immutable.Seq

case class TestTaskBuilder(
    task: Option[Task], instanceBuilder: TestInstanceBuilder, now: Timestamp = Timestamp.now()
) {

  def taskLaunched(container: Option[MesosContainer] = None) =
    this.copy(task = Some(TestTaskBuilder.Creator.minimalTask(instanceBuilder.getInstance().instanceId, container, now)))

  def taskReserved(reservation: Task.Reservation = TestTaskBuilder.Creator.newReservation) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Creator.minimalReservedTask(instance.instanceId.runSpecId, reservation, Some(instance))))
  }

  def taskRunning(container: Option[MesosContainer] = None, stagedAt: Timestamp = now, startedAt: Timestamp = now) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Creator.runningTask(
      Task.Id.forInstanceId(instance.instanceId, container),
      instance.runSpecVersion, stagedAt = stagedAt.toDateTime.getMillis, startedAt = startedAt.toDateTime.getMillis)))
  }

  def taskStaged(stagedAt: Timestamp = now) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Creator.stagedTask(Task.Id.forInstanceId(instance.instanceId, None), instance.runSpecVersion, stagedAt = stagedAt.toDateTime.getMillis)))
  }

  def withAgentInfo(update: Instance.AgentInfo => Instance.AgentInfo): TestTaskBuilder = this.copy(task = task.map(_.withAgentInfo(update)))

  def applyUpdate(update: TaskUpdateOperation): TestTaskBuilder = {
    val concreteTask = task.getOrElse(throw new IllegalArgumentException("No task defined for TaskBuilder"))
    concreteTask.update(update)
    this
  }

  def build(): TestInstanceBuilder = task match {
    case Some(concreteTask) => instanceBuilder.addTask(concreteTask)
    case None => instanceBuilder
  }
}

object TestTaskBuilder {

  def newBuilder(instanceBuilder: TestInstanceBuilder) = TestTaskBuilder(None, instanceBuilder)

  // TODO ju taken from MarathonTaskHelper ->
  object Creator {
    def minimalTask(appId: PathId): Task.LaunchedEphemeral = minimalTask(Task.Id.forRunSpec(appId))

    def minimalTask(instanceId: Instance.Id, container: Option[MesosContainer], now: Timestamp): Task.LaunchedEphemeral =
      minimalTask(Task.Id.forInstanceId(instanceId, container), now)

    def minimalTask(taskId: Task.Id, now: Timestamp = Timestamp.now(), mesosStatus: Option[TaskStatus] = None): Task.LaunchedEphemeral = {
      minimalTask(taskId, now, mesosStatus, if (mesosStatus.isDefined) MarathonTaskStatus(mesosStatus.get) else InstanceStatus.Created)
    }

    def minimalTask(taskId: Task.Id, now: Timestamp, mesosStatus: Option[TaskStatus], marathonTaskStatus: InstanceStatus): Task.LaunchedEphemeral = {
      Task.LaunchedEphemeral(
        taskId,
        Instance.AgentInfo(host = "host.some", agentId = None, attributes = Seq.empty),
        runSpecVersion = now,
        status = Task.Status(
          stagedAt = now,
          startedAt = None,
          mesosStatus = mesosStatus,
          taskStatus = marathonTaskStatus
        ),
        hostPorts = Seq.empty
      )
    }

    def minimalLostTask(appId: PathId, marathonTaskStatus: InstanceStatus = InstanceStatus.Gone, since: Timestamp = Timestamp.now()): Task.LaunchedEphemeral = {
      val taskId = Task.Id.forRunSpec(appId)
      val status = TaskStatusUpdateTestHelper.makeMesosTaskStatus(taskId, TaskState.TASK_LOST, maybeReason = Some(TaskStatus.Reason.REASON_RECONCILIATION), timestamp = since)
      minimalTask(
        taskId = taskId,
        now = since,
        mesosStatus = Some(status),
        marathonTaskStatus = marathonTaskStatus
      )
    }

    def minimalUnreachableTask(appId: PathId, marathonTaskStatus: InstanceStatus = InstanceStatus.Unreachable, since: Timestamp = Timestamp.now()): Task.LaunchedEphemeral = {
      val lostTask = minimalLostTask(appId = appId, since = since)
      lostTask.copy(status = lostTask.status.copy(taskStatus = marathonTaskStatus))
    }

    def minimalRunning(appId: PathId, marathonTaskStatus: InstanceStatus = InstanceStatus.Running, since: Timestamp = Timestamp.now()): Task.LaunchedEphemeral = {
      val taskId = Task.Id.forRunSpec(appId)
      val status = TaskStatusUpdateTestHelper.makeMesosTaskStatus(taskId, TaskState.TASK_RUNNING, maybeHealth = Option(true))
      minimalTask(
        taskId = taskId,
        now = since,
        mesosStatus = Some(status),
        marathonTaskStatus = marathonTaskStatus
      )
    }

    def minimalReservedTask(appId: PathId, reservation: Task.Reservation, instance: Option[Instance] = None): Task.Reserved =
      Task.Reserved(
        taskId = instance.map(i => Task.Id.forInstanceId(i.instanceId, None)).getOrElse(Task.Id.forRunSpec(appId)),
        Instance.AgentInfo(host = "host.some", agentId = None, attributes = Seq.empty),
        reservation = reservation,
        status = Task.Status(Timestamp.now(), taskStatus = InstanceStatus.Reserved))

    def newReservation: Task.Reservation = Task.Reservation(Seq.empty, taskReservationStateNew)

    def taskReservationStateNew = Task.Reservation.State.New(timeout = None)

    def taskLaunched: Task.Launched = {
      val now = Timestamp.now()
      Task.Launched(now, status = Task.Status(stagedAt = now, taskStatus = InstanceStatus.Running), hostPorts = Seq.empty)
    }

    def residentReservedTask(appId: PathId, localVolumeIds: Task.LocalVolumeId*) =
      minimalReservedTask(appId, Task.Reservation(localVolumeIds, taskReservationStateNew))

    def residentLaunchedTask(appId: PathId, localVolumeIds: Task.LocalVolumeId*) = {
      val now = Timestamp.now()
      Task.LaunchedOnReservation(
        taskId = Task.Id.forRunSpec(appId),
        agentInfo = Instance.AgentInfo(host = "host.some", agentId = None, attributes = Seq.empty),
        runSpecVersion = now,
        status = Task.Status(
          stagedAt = now,
          startedAt = None,
          mesosStatus = None,
          taskStatus = InstanceStatus.Running
        ),
        hostPorts = Seq.empty,
        reservation = Task.Reservation(localVolumeIds, Task.Reservation.State.Launched))
    }

    def startingTaskForApp(appId: PathId, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Task.LaunchedEphemeral =
      startingTask(
        Task.Id.forRunSpec(appId),
        appVersion = appVersion,
        stagedAt = stagedAt
      )

    def startingTask(taskId: Task.Id, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Task.LaunchedEphemeral =
      Task.LaunchedEphemeral(
        taskId = taskId,
        agentInfo = Instance.AgentInfo("some.host", Some("agent-1"), Seq.empty),
        runSpecVersion = appVersion,
        status = Task.Status(
          stagedAt = Timestamp(stagedAt),
          startedAt = None,
          mesosStatus = Some(statusForState(taskId.idString, TaskState.TASK_STARTING)),
          taskStatus = InstanceStatus.Starting
        ),
        hostPorts = Seq.empty
      )

    def stagedTaskForApp(
      appId: PathId = PathId("/test"), appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Task.LaunchedEphemeral =
      stagedTask(Task.Id.forRunSpec(appId), appVersion = appVersion, stagedAt = stagedAt)

    def stagedTask(
      taskId: Task.Id,
      appVersion: Timestamp = Timestamp(1),
      stagedAt: Long = 2): Task.LaunchedEphemeral =
      Task.LaunchedEphemeral(
        taskId = taskId,
        agentInfo = Instance.AgentInfo("some.host", Some("agent-1"), Seq.empty),
        runSpecVersion = appVersion,
        status = Task.Status(
          stagedAt = Timestamp(stagedAt),
          startedAt = None,
          mesosStatus = Some(statusForState(taskId.idString, TaskState.TASK_STAGING)),
          taskStatus = InstanceStatus.Staging
        ),
        hostPorts = Seq.empty
      )

    def statusForState(taskId: String, state: TaskState, maybeReason: Option[TaskStatus.Reason] = None): TaskStatus = {
      val builder = TaskStatus
        .newBuilder()
        .setTaskId(TaskID.newBuilder().setValue(taskId))
        .setState(state)

      maybeReason.foreach(builder.setReason)

      builder.buildPartial()
    }

    def runningTaskForApp(
      appId: PathId = PathId("/test"),
      appVersion: Timestamp = Timestamp(1),
      stagedAt: Long = 2,
      startedAt: Long = 3): Task.LaunchedEphemeral =
      runningTask(
        Task.Id.forRunSpec(appId),
        appVersion = appVersion,
        stagedAt = stagedAt,
        startedAt = startedAt
      )

    def runningTask(
      taskId: Task.Id,
      appVersion: Timestamp = Timestamp(1),
      stagedAt: Long = 2,
      startedAt: Long = 3): Task.LaunchedEphemeral = {
      import mesosphere.marathon.test.MarathonTestHelper.Implicits._

      startingTask(taskId, appVersion, stagedAt)
        .withStatus((status: Task.Status) =>
          status.copy(
            startedAt = Some(Timestamp(startedAt)),
            mesosStatus = Some(statusForState(taskId.idString, TaskState.TASK_RUNNING))
          )
        )

    }

    def healthyTask(appId: PathId): Task.LaunchedEphemeral = healthyTask(Task.Id.forRunSpec(appId))

    def healthyTask(taskId: Task.Id): Task.LaunchedEphemeral = {
      import mesosphere.marathon.test.MarathonTestHelper.Implicits._

      runningTask(taskId).withStatus { status =>
        status.copy(mesosStatus = status.mesosStatus.map(_.toBuilder.setHealthy(true).build()))
      }
    }

    def unhealthyTask(appId: PathId): Task.LaunchedEphemeral = unhealthyTask(Task.Id.forRunSpec(appId))

    def unhealthyTask(taskId: Task.Id): Task.LaunchedEphemeral = {
      import mesosphere.marathon.test.MarathonTestHelper.Implicits._

      runningTask(taskId).withStatus { status =>
        status.copy(mesosStatus = status.mesosStatus.map(_.toBuilder.setHealthy(false).build()))
      }
    }

    def lostTask(id: String): MarathonTask = {
      MarathonTask
        .newBuilder()
        .setId(id)
        .setStatus(statusForState(id, TaskState.TASK_LOST))
        .buildPartial()
    }
  }

}