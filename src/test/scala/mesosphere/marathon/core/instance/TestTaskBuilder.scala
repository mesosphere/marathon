package mesosphere.marathon
package core.instance

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.state.{ NetworkInfo, NetworkInfoPlaceholder }
import mesosphere.marathon.core.task.update.TaskUpdateOperation
import mesosphere.marathon.core.task.{ Task, TaskCondition }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.marathon.test.MarathonTestHelper.Implicits._
import org.apache.mesos
import org.slf4j.LoggerFactory

case class TestTaskBuilder(
    task: Option[Task], instanceBuilder: TestInstanceBuilder, now: Timestamp = Timestamp.now()
) {

  def taskFromTaskInfo(
    taskInfo: mesos.Protos.TaskInfo,
    offer: mesos.Protos.Offer = MarathonTestHelper.makeBasicOffer().build(),
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

  def taskResidentUnreachable(localVolumeIds: Task.LocalVolumeId*) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.residentUnreachableTask(instance.instanceId.runSpecId, localVolumeIds: _*).copy(taskId = Task.Id.forInstanceId(instance.instanceId, None))))
  }

  def taskRunning(containerName: Option[String] = None, stagedAt: Timestamp = now, startedAt: Timestamp = now) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.runningTask(
      Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName)),
      instance.runSpecVersion, stagedAt = stagedAt.millis, startedAt = startedAt.millis)))
  }

  /**
    * Creates a task with Condition.Unreachable and Mesos status TASK_LOST for backwards compatibility tests.
    *
    * @param since Mesos status timestamp.
    * @param containerName the name of the container
    * @return
    */
  def taskLost(since: Timestamp = now, containerName: Option[String] = None) = {
    val instance = instanceBuilder.getInstance()
    val task = TestTaskBuilder.Helper.minimalLostTask(instance.instanceId.runSpecId, since = since, taskCondition = Condition.Unreachable)
    this.copy(task = Some(task.copy(taskId = Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName)))))
  }

  /**
    * Creates a task with Condition.Unreachable and Mesos status TASK_UNREACHABLE.
    *
    * @param since Mesos status timestamp AND unreachable time.
    * @return
    */
  def taskUnreachable(since: Timestamp = now, containerName: Option[String] = None) = {
    val instance = instanceBuilder.getInstance()
    val task = TestTaskBuilder.Helper.minimalUnreachableTask(instance.instanceId.runSpecId, since = since)
    this.copy(task = Some(task.copy(taskId = Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName)))))
  }

  /**
    * Creates a task with Condition.UnreachableInactive and Mesos status TASK_UNREACHABLE.
    *
    * @param since Mesos status timestamp AND unreachable time.
    * @return
    */
  def taskUnreachableInactive(since: Timestamp = now, containerName: Option[String] = None) = {
    val instance = instanceBuilder.getInstance()
    val taskId = Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName))
    val task = TestTaskBuilder.Helper.minimalUnreachableTask(instance.instanceId.runSpecId, Condition.UnreachableInactive, since).copy(taskId = taskId)
    this.copy(task = Some(task))
  }

  def mesosStatusForCondition(condition: Condition, taskId: Task.Id): Option[mesos.Protos.TaskStatus] = condition match {
    case Condition.Created => None
    case Condition.Dropped => Some(MesosTaskStatusTestHelper.dropped(taskId))
    case Condition.Error => Some(MesosTaskStatusTestHelper.error(taskId))
    case Condition.Failed => Some(MesosTaskStatusTestHelper.failed(taskId))
    case Condition.Finished => Some(MesosTaskStatusTestHelper.finished(taskId))
    case Condition.Gone => Some(MesosTaskStatusTestHelper.gone(taskId))
    case Condition.Killed => Some(MesosTaskStatusTestHelper.killed(taskId))
    case Condition.Killing => Some(MesosTaskStatusTestHelper.killing(taskId))
    case Condition.Reserved => None
    case Condition.Running => Some(MesosTaskStatusTestHelper.running(taskId))
    case Condition.Staging => Some(MesosTaskStatusTestHelper.staging(taskId))
    case Condition.Starting => Some(MesosTaskStatusTestHelper.starting(taskId))
    case Condition.Unknown => Some(MesosTaskStatusTestHelper.unknown(taskId))
    case Condition.Unreachable => Some(MesosTaskStatusTestHelper.unreachable(taskId))
    case Condition.UnreachableInactive => Some(MesosTaskStatusTestHelper.unreachable(taskId))
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
    this.copy(task = Some(TestTaskBuilder.Helper.stagedTask(Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName)), version.getOrElse(instance.runSpecVersion), stagedAt = stagedAt.millis)))
  }

  def taskStarting(stagedAt: Timestamp = now, containerName: Option[String] = None) = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.startingTaskForApp(instance.instanceId, stagedAt = stagedAt.millis, container = maybeMesosContainerByName(containerName))))
  }

  private def createTask(since: Timestamp, containerName: Option[String], condition: Condition) = {
    val instance = instanceBuilder.getInstance()
    val taskId = Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName))
    val mesosStatus = mesosStatusForCondition(condition, taskId)
    this.copy(task = Some(TestTaskBuilder.Helper.minimalTask(taskId, since, mesosStatus, condition)))
  }

  def withNetworkInfo(networkInfo: NetworkInfo): TestTaskBuilder =
    copy(task = task.map(_.withNetworkInfo(networkInfo)))

  def withNetworkInfo(
    hostName: Option[String] = None,
    hostPorts: Seq[Int] = Nil,
    networkInfos: scala.collection.Seq[mesos.Protos.NetworkInfo] = Nil): TestTaskBuilder =
    copy(task = task.map(_.withNetworkInfo(hostName, hostPorts, networkInfos)))

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

  private[this] val log = LoggerFactory.getLogger(getClass)

  def newBuilder(instanceBuilder: TestInstanceBuilder) = TestTaskBuilder(None, instanceBuilder)

  object Helper {
    def makeTaskFromTaskInfo(
      taskInfo: mesos.Protos.TaskInfo,
      offer: mesos.Protos.Offer = MarathonTestHelper.makeBasicOffer().build(),
      version: Timestamp = Timestamp(10), now: Timestamp = Timestamp(10),
      taskCondition: Condition = Condition.Staging): Task.LaunchedEphemeral = {

      log.debug(s"offer: $offer")
      Task.LaunchedEphemeral(
        taskId = Task.Id(taskInfo.getTaskId),
        runSpecVersion = version,
        status = Task.Status(
          stagedAt = now,
          condition = taskCondition,
          networkInfo = NetworkInfo(hostName = "host.some", hostPorts = Seq(1, 2, 3), ipAddresses = Nil)
        )
      )
    }

    def minimalTask(appId: PathId): Task.LaunchedEphemeral = minimalTask(Task.Id.forRunSpec(appId))

    def minimalTask(instanceId: Instance.Id, container: Option[MesosContainer], now: Timestamp): Task.LaunchedEphemeral =
      minimalTask(Task.Id.forInstanceId(instanceId, container), now)

    def minimalTask(taskId: Task.Id, now: Timestamp = Timestamp.now(), mesosStatus: Option[mesos.Protos.TaskStatus] = None): Task.LaunchedEphemeral = {
      minimalTask(taskId, now, mesosStatus, if (mesosStatus.isDefined) TaskCondition(mesosStatus.get) else Condition.Created)
    }

    def minimalTask(taskId: Task.Id, now: Timestamp, mesosStatus: Option[mesos.Protos.TaskStatus], taskCondition: Condition): Task.LaunchedEphemeral = {
      Task.LaunchedEphemeral(
        taskId,
        runSpecVersion = now,
        status = Task.Status(
          stagedAt = now,
          startedAt = None,
          mesosStatus = mesosStatus,
          condition = taskCondition,
          networkInfo = NetworkInfo("host.some", hostPorts = Nil, ipAddresses = Nil)
        )
      )
    }

    def minimalLostTask(appId: PathId, taskCondition: Condition = Condition.Gone, since: Timestamp = Timestamp.now()): Task.LaunchedEphemeral = {
      val taskId = Task.Id.forRunSpec(appId)
      val status = MesosTaskStatusTestHelper.lost(mesos.Protos.TaskStatus.Reason.REASON_RECONCILIATION, taskId, since)
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
      val status = MesosTaskStatusTestHelper.mesosStatus(state = mesos.Protos.TaskState.TASK_RUNNING, maybeHealthy = Option(true), taskId = taskId)
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
        reservation = reservation,
        status = Task.Status(Timestamp.now(), condition = Condition.Reserved, networkInfo = NetworkInfoPlaceholder()),
        runSpecVersion = Timestamp.now())

    def newReservation: Task.Reservation = Task.Reservation(Seq.empty, taskReservationStateNew)

    def taskReservationStateNew = Task.Reservation.State.New(timeout = None)

    def residentReservedTask(appId: PathId, taskReservationState: Task.Reservation.State, localVolumeIds: Task.LocalVolumeId*) =
      minimalReservedTask(appId, Task.Reservation(localVolumeIds.to[Seq], taskReservationState))

    def residentLaunchedTask(appId: PathId, localVolumeIds: Task.LocalVolumeId*) = {
      val now = Timestamp.now()
      Task.LaunchedOnReservation(
        taskId = Task.Id.forRunSpec(appId),
        runSpecVersion = now,
        status = Task.Status(
          stagedAt = now,
          startedAt = Some(now),
          mesosStatus = None,
          condition = Condition.Running,
          networkInfo = NetworkInfoPlaceholder()
        ),
        reservation = Task.Reservation(localVolumeIds.to[Seq], Task.Reservation.State.Launched))
    }

    def residentUnreachableTask(appId: PathId, localVolumeIds: Task.LocalVolumeId*) = {
      val now = Timestamp.now()
      Task.LaunchedOnReservation(
        taskId = Task.Id.forRunSpec(appId),
        runSpecVersion = now,
        status = Task.Status(
          stagedAt = now,
          startedAt = Some(now),
          mesosStatus = None,
          condition = Condition.Unreachable,
          networkInfo = NetworkInfoPlaceholder()
        ),
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
        runSpecVersion = appVersion,
        status = Task.Status(
          stagedAt = Timestamp(stagedAt),
          startedAt = None,
          mesosStatus = Some(statusForState(taskId.idString, mesos.Protos.TaskState.TASK_STARTING)),
          condition = Condition.Starting,
          networkInfo = NetworkInfoPlaceholder()
        )
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
        runSpecVersion = appVersion,
        status = Task.Status(
          stagedAt = Timestamp(stagedAt),
          startedAt = None,
          mesosStatus = Some(statusForState(taskId.idString, mesos.Protos.TaskState.TASK_STAGING)),
          condition = Condition.Staging,
          networkInfo = NetworkInfoPlaceholder()
        )
      )

    def statusForState(taskId: String, state: mesos.Protos.TaskState, maybeReason: Option[mesos.Protos.TaskStatus.Reason] = None): mesos.Protos.TaskStatus = {
      val builder = mesos.Protos.TaskStatus
        .newBuilder()
        .setTaskId(mesos.Protos.TaskID.newBuilder().setValue(taskId))
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
            mesosStatus = Some(statusForState(taskId.idString, mesos.Protos.TaskState.TASK_RUNNING))
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
            condition = Condition.Killed,
            startedAt = Some(Timestamp(startedAt)),
            mesosStatus = Some(statusForState(taskId.idString, mesos.Protos.TaskState.TASK_KILLED))
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
        .setStatus(statusForState(id, mesos.Protos.TaskState.TASK_LOST))
        .buildPartial()
    }
  }

}
