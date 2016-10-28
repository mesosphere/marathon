package mesosphere.marathon
package core.instance

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.update.TaskUpdateOperation
import mesosphere.marathon.core.task.{ TaskCondition, Task }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.stream._
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.marathon.test.MarathonTestHelper.Implicits._
import org.apache.mesos
import org.apache.mesos.Protos._

case class TestTaskBuilder(
    task: Option[Task], instanceBuilder: TestInstanceBuilder, now: Timestamp = Timestamp.now()
) {

  def taskFromTaskInfo(
    taskInfo: TaskInfo,
    offer: Offer = MarathonTestHelper.makeBasicOffer().build(),
    version: Timestamp = Timestamp(10),
    taskCondition: Condition = Condition.Staging) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.makeTaskFromTaskInfo(taskInfo, offer, version, now, taskCondition).copy(taskId = Task.Id.forInstanceId(instance.instanceId, None))))
  }

  def taskForStatus(mesosState: mesos.Protos.TaskState, stagedAt: Timestamp = now, container: Option[MesosContainer] = None) = {
    val instance = instanceBuilder.getInstance()
    val taskId = Task.Id.forInstanceId(instance.instanceId, container)
    val mesosStatus = TestTaskBuilder.Helper.statusForState(taskId.idString, mesosState)
    this.copy(task = Some(TestTaskBuilder.Helper.minimalTask(taskId, stagedAt, Some(mesosStatus))))
  }

  def maybeMesosContainerByName(name: Option[String]): Option[MesosContainer] = name.map(n => MesosContainer(name = n, resources = raml.Resources()))

  def taskLaunched(container: Option[MesosContainer] = None) =
    this.copy(task = Some(TestTaskBuilder.Helper.minimalTask(instanceBuilder.getInstance().instanceId, container, now).copy(taskId = Task.Id.forInstanceId(instanceBuilder.getInstance().instanceId, None))))

  def taskReserved(reservation: Task.Reservation = TestTaskBuilder.Helper.newReservation, containerName: Option[String] = None) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.minimalReservedTask(instance.instanceId.runSpecId, reservation, Some(instance)).copy(taskId = Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName)))))
  }

  def taskResidentReserved(localVolumeIds: Task.LocalVolumeId*) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.residentReservedTask(instance.instanceId.runSpecId, TestTaskBuilder.Helper.taskReservationStateNew, localVolumeIds: _*).copy(taskId = Task.Id.forInstanceId(instance.instanceId, None))))
  }

  def taskResidentReserved(taskReservationState: Task.Reservation.State) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.residentReservedTask(instance.instanceId.runSpecId, taskReservationState, Seq.empty[Task.LocalVolumeId]: _*).copy(taskId = Task.Id.forInstanceId(instance.instanceId, None))))
  }

  def taskResidentLaunched(localVolumeIds: Task.LocalVolumeId*) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.residentLaunchedTask(instance.instanceId.runSpecId, localVolumeIds: _*).copy(taskId = Task.Id.forInstanceId(instance.instanceId, None))))
  }

  def taskRunning(containerName: Option[String] = None, stagedAt: Timestamp = now, startedAt: Timestamp = now) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.runningTask(
      Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName)),
      instance.runSpecVersion, stagedAt = stagedAt.toDateTime.getMillis, startedAt = startedAt.toDateTime.getMillis).withAgentInfo(_ => instance.agentInfo)))
  }

  /**
    * Creates a task with Condition.Unreachable and Mesos status TASK_LOST for backwards compatibility tests.
    *
    * @param since Mesos status timestamp.
    * @param containerName
    * @return
    */
  def taskLost(since: Timestamp = now, containerName: Option[String] = None) = {
    val instance = instanceBuilder.getInstance()
    val task = TestTaskBuilder.Helper.minimalLostTask(instance.instanceId.runSpecId, since = since, taskCondition = Condition.Unreachable)
    this.copy(task = Some(task.copy(taskId = Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName))).withAgentInfo(_ => instance.agentInfo)))
  }

  /**
    * Creates a task with Condition.Unreachable and Mesos status TASK_UNREACHABLE.
    *
    * @param since Mesos status timestamp AND unreachable time.
    * @param containerName
    * @return
    */
  def taskUnreachable(since: Timestamp = now, containerName: Option[String] = None) = {
    val instance = instanceBuilder.getInstance()
    val task = TestTaskBuilder.Helper.minimalUnreachableTask(instance.instanceId.runSpecId, since = since)
    this.copy(task = Some(task.copy(taskId = Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName))).withAgentInfo(_ => instance.agentInfo)))
  }

  def taskError(since: Timestamp = now, containerName: Option[String] = None) = createTask(since, containerName, Condition.Error)

  def taskFailed(since: Timestamp = now, containerName: Option[String] = None) = createTask(since, containerName, Condition.Failed)

  def taskFinished(since: Timestamp = now, containerName: Option[String] = None) = createTask(since, containerName, Condition.Finished)

  def taskKilled(since: Timestamp = now, containerName: Option[String] = None) = createTask(since, containerName, Condition.Killed)

  def taskDropped(since: Timestamp = now, containerName: Option[String] = None) = createTask(since, containerName, Condition.Dropped)

  def taskUnknown(since: Timestamp = now, containerName: Option[String] = None) = createTask(since, containerName, Condition.Unknown)

  def taskGone(since: Timestamp = now, containerName: Option[String] = None) = createTask(since, containerName, Condition.Gone)

  def taskCreated(since: Timestamp = now, containerName: Option[String] = None) = createTask(since, containerName, Condition.Created)

  def taskKilling(since: Timestamp = now, containerName: Option[String] = None) = createTask(since, containerName, Condition.Killing)

  def taskStaging(since: Timestamp = now, containerName: Option[String] = None) = createTask(since, containerName, Condition.Staging)

  def taskStaged(containerName: Option[String] = None, stagedAt: Timestamp = now, version: Option[Timestamp] = None) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.stagedTask(Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName)), version.getOrElse(instance.runSpecVersion), stagedAt = stagedAt.toDateTime.getMillis).withAgentInfo(_ => instance.agentInfo)))
  }

  def taskStarting(stagedAt: Timestamp = now, containerName: Option[String] = None) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.startingTaskForApp(instance.instanceId, stagedAt = stagedAt.toDateTime.getMillis, container = maybeMesosContainerByName(containerName)).withAgentInfo(_ => instance.agentInfo)))
  }

  private def createTask(since: Timestamp, containerName: Option[String], condition: Condition) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.minimalTask(Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName)), since, None, condition).withAgentInfo(_ => instance.agentInfo)))
  }

  def withAgentInfo(update: Instance.AgentInfo => Instance.AgentInfo): TestTaskBuilder = this.copy(task = task.map(_.withAgentInfo(update)))

  def withHostPorts(update: Seq[Int]): TestTaskBuilder = this.copy(task = task.map(_.withHostPorts(update)))

  def withNetworkInfos(update: scala.collection.Seq[NetworkInfo]): TestTaskBuilder = this.copy(task = task.map(_.withNetworkInfos(update)))

  def asHealthyTask(): TestTaskBuilder = {
    import mesosphere.marathon.test.MarathonTestHelper.Implicits._
    this.copy(task = task match {
      case Some(t: Task) => Some(t.withStatus(status => status.copy(mesosStatus = status.mesosStatus.map(_.toBuilder.setHealthy(true).build()))))
      case None => None
    })
  }

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

  object Helper {
    def makeTaskFromTaskInfo(
      taskInfo: TaskInfo,
      offer: Offer = MarathonTestHelper.makeBasicOffer().build(),
      version: Timestamp = Timestamp(10), now: Timestamp = Timestamp(10),
      taskCondition: Condition = Condition.Staging): Task.LaunchedEphemeral = {

      Task.LaunchedEphemeral(
        taskId = Task.Id(taskInfo.getTaskId),
        agentInfo = Instance.AgentInfo(
          host = offer.getHostname,
          agentId = Some(offer.getSlaveId.getValue),
          attributes = offer.getAttributesList.toIndexedSeq
        ),
        runSpecVersion = version,
        status = Task.Status(
          stagedAt = now,
          condition = taskCondition
        ),
        hostPorts = Seq(1, 2, 3)
      )
    }

    def minimalTask(appId: PathId): Task.LaunchedEphemeral = minimalTask(Task.Id.forRunSpec(appId))

    def minimalTask(instanceId: Instance.Id, container: Option[MesosContainer], now: Timestamp): Task.LaunchedEphemeral =
      minimalTask(Task.Id.forInstanceId(instanceId, container), now)

    def minimalTask(taskId: Task.Id, now: Timestamp = Timestamp.now(), mesosStatus: Option[TaskStatus] = None): Task.LaunchedEphemeral = {
      minimalTask(taskId, now, mesosStatus, if (mesosStatus.isDefined) TaskCondition(mesosStatus.get) else Condition.Created)
    }

    def minimalTask(taskId: Task.Id, now: Timestamp, mesosStatus: Option[TaskStatus], taskCondition: Condition): Task.LaunchedEphemeral = {
      Task.LaunchedEphemeral(
        taskId,
        Instance.AgentInfo(host = "host.some", agentId = None, attributes = Seq.empty),
        runSpecVersion = now,
        status = Task.Status(
          stagedAt = now,
          startedAt = None,
          mesosStatus = mesosStatus,
          condition = taskCondition
        ),
        hostPorts = Seq.empty
      )
    }

    def minimalLostTask(appId: PathId, taskCondition: Condition = Condition.Gone, since: Timestamp = Timestamp.now()): Task.LaunchedEphemeral = {
      val taskId = Task.Id.forRunSpec(appId)
      val status = MesosTaskStatusTestHelper.lost(TaskStatus.Reason.REASON_RECONCILIATION, taskId, since)
      minimalTask(
        taskId = taskId,
        now = since,
        mesosStatus = Some(status),
        taskCondition = taskCondition
      )
    }

    def minimalUnreachableTask(appId: PathId, taskCondition: Condition = Condition.Unreachable, since: Timestamp = Timestamp.now()): Task.LaunchedEphemeral = {
      val lostTask = minimalLostTask(appId = appId, since = since)
      val mesosStatus = MesosTaskStatusTestHelper.unreachable(taskId = lostTask.taskId, since = since)
      val status = lostTask.status.copy(condition = taskCondition, mesosStatus = Some(mesosStatus))
      lostTask.copy(status = status)
    }

    def minimalRunning(appId: PathId, taskCondition: Condition = Condition.Running, since: Timestamp = Timestamp.now()): Task.LaunchedEphemeral = {
      val taskId = Task.Id.forRunSpec(appId)
      val status = MesosTaskStatusTestHelper.mesosStatus(state = TaskState.TASK_RUNNING, maybeHealthy = Option(true), taskId = taskId)
      minimalTask(
        taskId = taskId,
        now = since,
        mesosStatus = Some(status),
        taskCondition = taskCondition
      )
    }

    def minimalReservedTask(appId: PathId, reservation: Task.Reservation, instance: Option[Instance] = None): Task.Reserved =
      Task.Reserved(
        taskId = instance.map(i => Task.Id.forInstanceId(i.instanceId, None)).getOrElse(Task.Id.forRunSpec(appId)),
        Instance.AgentInfo(host = "host.some", agentId = None, attributes = Seq.empty),
        reservation = reservation,
        status = Task.Status(Timestamp.now(), condition = Condition.Reserved),
        runSpecVersion = Timestamp.now())

    def newReservation: Task.Reservation = Task.Reservation(Seq.empty, taskReservationStateNew)

    def taskReservationStateNew = Task.Reservation.State.New(timeout = None)

    def taskLaunched: Task.Launched = {
      Task.Launched(hostPorts = Seq.empty)
    }

    def residentReservedTask(appId: PathId, taskReservationState: Task.Reservation.State, localVolumeIds: Task.LocalVolumeId*) =
      minimalReservedTask(appId, Task.Reservation(localVolumeIds.to[Seq], taskReservationState))

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
          condition = Condition.Running
        ),
        hostPorts = Seq.empty,
        reservation = Task.Reservation(localVolumeIds.to[Seq], Task.Reservation.State.Launched))
    }

    def startingTaskForApp(instanceId: Instance.Id, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2, container: Option[MesosContainer] = None): Task.LaunchedEphemeral =
      startingTask(
        Task.Id.forInstanceId(instanceId, container),
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
          condition = Condition.Starting
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
          condition = Condition.Staging
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

    def killedTask(
      taskId: Task.Id,
      appVersion: Timestamp = Timestamp(1),
      stagedAt: Long = 2,
      startedAt: Long = 3): Task.LaunchedEphemeral = {

      startingTask(taskId, appVersion, stagedAt)
        .withStatus((status: Task.Status) =>
          status.copy(
            startedAt = Some(Timestamp(startedAt)),
            mesosStatus = Some(statusForState(taskId.idString, TaskState.TASK_KILLED))
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