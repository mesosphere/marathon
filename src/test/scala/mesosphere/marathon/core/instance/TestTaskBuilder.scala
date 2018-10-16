package mesosphere.marathon
package core.instance

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.pod.MesosContainer
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.state.{NetworkInfo, NetworkInfoPlaceholder}
import mesosphere.marathon.core.task.{Task, TaskCondition}
import mesosphere.marathon.state.{PathId, Timestamp}
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.marathon.test.MarathonTestHelper.Implicits._
import org.apache.mesos

case class TestTaskBuilder(task: Option[Task], instanceBuilder: TestInstanceBuilder) {

  val now: Timestamp = instanceBuilder.now

  def taskFromTaskInfo(
    taskInfo: mesos.Protos.TaskInfo,
    offer: mesos.Protos.Offer = MarathonTestHelper.makeBasicOffer().build(),
    version: Timestamp = Timestamp(10),
    taskCondition: Condition = Condition.Staging): TestTaskBuilder = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.makeTaskFromTaskInfo(
      taskInfo, offer, version, now, taskCondition).copy(
      taskId = Task.Id.forInstanceId(instance.instanceId))))
  }

  def taskForStatus(mesosState: mesos.Protos.TaskState, stagedAt: Timestamp = now,
    container: Option[MesosContainer] = None): TestTaskBuilder = {
    val instance = instanceBuilder.getInstance()
    val taskId = Task.Id.forInstanceId(instance.instanceId, container)
    val mesosStatus = TestTaskBuilder.Helper.statusForState(taskId.idString, mesosState)
    this.copy(task = Some(TestTaskBuilder.Helper.minimalTask(taskId, stagedAt, Some(mesosStatus))))
  }

  def maybeMesosContainerByName(name: Option[String]): Option[MesosContainer] =
    name.map(n => MesosContainer(name = n, resources = raml.Resources()))

  def taskLaunched(container: Option[MesosContainer] = None): TestTaskBuilder =
    this.copy(task = Some(TestTaskBuilder.Helper.minimalTask(
      instanceBuilder.getInstance().instanceId, container, now).copy(
        taskId = Task.Id.forInstanceId(instanceBuilder.getInstance().instanceId))))

  def taskResidentLaunched(): TestTaskBuilder = {
    val instance = instanceBuilder.getInstance()
    val taskId = Task.Id.forInstanceId(instance.instanceId)
    this.copy(task = Some(TestTaskBuilder.Helper.residentLaunchedTask(instance.instanceId.runSpecId, Some(taskId))))
  }

  def taskReserved(containerName: Option[String] = None): TestTaskBuilder = {
    val instance = instanceBuilder.getInstance()
    val taskId = Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName))
    this.copy(task = Some(TestTaskBuilder.Helper.residentReservedTask(instance.instanceId.runSpecId, Some(taskId))))
  }

  def taskResidentReserved(): TestTaskBuilder = {
    val instance = instanceBuilder.getInstance()
    val taskId = Task.Id.forInstanceId(instance.instanceId)
    this.copy(task = Some(TestTaskBuilder.Helper.residentReservedTask(instance.instanceId.runSpecId, Some(taskId))))
  }

  def taskUnreachable(): TestTaskBuilder = {
    val instance = instanceBuilder.getInstance()
    val taskId = Task.Id.forInstanceId(instance.instanceId)
    this.copy(task = Some(TestTaskBuilder.Helper.unreachableTask(instance.instanceId.runSpecId, Some(taskId))))
  }

  def taskRunning(containerName: Option[String] = None, stagedAt: Timestamp = now,
    startedAt: Timestamp = now): TestTaskBuilder = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.runningTask(instance.instanceId, instance.runSpecVersion,
      stagedAt = stagedAt.millis, startedAt = startedAt.millis, maybeMesosContainerByName(containerName))))
  }

  /**
    * Creates a task with Condition.Unreachable and Mesos status TASK_LOST for backwards compatibility tests.
    *
    * @param since Mesos status timestamp.
    * @param containerName the name of the container
    * @return
    */
  def taskLost(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder = {
    val instance = instanceBuilder.getInstance()
    val task = TestTaskBuilder.Helper.minimalLostTask(
      instance.instanceId.runSpecId, since = since, taskCondition = Condition.Unreachable)
    this.copy(task = Some(task.copy(
      taskId = Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName)))))
  }

  /**
    * Creates a task with Condition.Unreachable and Mesos status TASK_UNREACHABLE.
    *
    * @param since Mesos status timestamp AND unreachable time.
    * @return
    */
  def taskUnreachable(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder = {
    val instance = instanceBuilder.getInstance()
    val task = TestTaskBuilder.Helper.minimalUnreachableTask(instance.instanceId.runSpecId, since = since)
    this.copy(task = Some(task.copy(
      taskId = Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName)))))
  }

  /**
    * Creates a task with Condition.UnreachableInactive and Mesos status TASK_UNREACHABLE.
    *
    * @param since Mesos status timestamp AND unreachable time.
    * @return
    */
  def taskUnreachableInactive(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder = {
    val instance = instanceBuilder.getInstance()
    val taskId = Task.Id.forInstanceId(instance.instanceId, maybeMesosContainerByName(containerName))
    val task = TestTaskBuilder.Helper.minimalUnreachableTask(
      instance.instanceId.runSpecId, Condition.UnreachableInactive, since).copy(taskId = taskId)
    this.copy(task = Some(task))
  }

  def mesosStatusForCondition(condition: Condition, taskId: Task.Id): Option[mesos.Protos.TaskStatus] =
    condition match {
      case Condition.Provisioned => None
      case Condition.Dropped => Some(MesosTaskStatusTestHelper.dropped(taskId))
      case Condition.Error => Some(MesosTaskStatusTestHelper.error(taskId))
      case Condition.Failed => Some(MesosTaskStatusTestHelper.failed(taskId))
      case Condition.Finished => Some(MesosTaskStatusTestHelper.finished(taskId))
      case Condition.Gone => Some(MesosTaskStatusTestHelper.gone(taskId))
      case Condition.Killed => Some(MesosTaskStatusTestHelper.killed(taskId))
      case Condition.Killing => Some(MesosTaskStatusTestHelper.killing(taskId))
      case Condition.Running => Some(MesosTaskStatusTestHelper.running(taskId))
      case Condition.Staging => Some(MesosTaskStatusTestHelper.staging(taskId))
      case Condition.Starting => Some(MesosTaskStatusTestHelper.starting(taskId))
      case Condition.Unknown => Some(MesosTaskStatusTestHelper.unknown(taskId))
      case Condition.Unreachable => Some(MesosTaskStatusTestHelper.unreachable(taskId))
      case Condition.UnreachableInactive => Some(MesosTaskStatusTestHelper.unreachable(taskId))
      case _ => throw new UnsupportedOperationException(s"Unknown condition $condition")
    }

  def taskError(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder =
    createTask(since, containerName, Condition.Error)

  def taskFailed(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder =
    createTask(since, containerName, Condition.Failed)

  def taskFinished(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder =
    createTask(since, containerName, Condition.Finished)

  def taskKilled(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder =
    createTask(since, containerName, Condition.Killed)

  def taskDropped(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder =
    createTask(since, containerName, Condition.Dropped)

  def taskUnknown(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder =
    createTask(since, containerName, Condition.Unknown)

  def taskGone(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder =
    createTask(since, containerName, Condition.Gone)

  def taskProvisioned(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder =
    createTask(since, containerName, Condition.Provisioned)

  def taskKilling(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder =
    createTask(since, containerName, Condition.Killing)

  def taskStaging(since: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder =
    createTask(since, containerName, Condition.Staging)

  def taskStaged(containerName: Option[String] = None, stagedAt: Timestamp = now,
    version: Option[Timestamp] = None): TestTaskBuilder = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.stagedTask(instance.instanceId, version.getOrElse(instance.runSpecVersion),
      stagedAt = stagedAt.millis, maybeMesosContainerByName(containerName))))
  }

  def taskStarting(stagedAt: Timestamp = now, containerName: Option[String] = None): TestTaskBuilder = {
    val instance = instanceBuilder.getInstance()
    this.copy(task = Some(TestTaskBuilder.Helper.startingTaskForApp(
      instance.instanceId, stagedAt = stagedAt.millis, container = maybeMesosContainerByName(containerName))))
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
      case Some(t: Task) => Some(t.withStatus(status =>
        status.copy(mesosStatus = status.mesosStatus.map(_.toBuilder.setHealthy(true).build()))))
      case None => None
    })
  }

  def build(): TestInstanceBuilder = task match {
    case Some(concreteTask) => instanceBuilder.addTask(concreteTask)
    case None => instanceBuilder
  }
}

object TestTaskBuilder extends StrictLogging {

  def newBuilder(instanceBuilder: TestInstanceBuilder) = TestTaskBuilder(None, instanceBuilder)

  object Helper {
    def makeTaskFromTaskInfo(
      taskInfo: mesos.Protos.TaskInfo,
      offer: mesos.Protos.Offer = MarathonTestHelper.makeBasicOffer().build(),
      version: Timestamp = Timestamp(10), now: Timestamp = Timestamp(10),
      taskCondition: Condition = Condition.Staging): Task = {

      logger.debug(s"offer: $offer")
      Task(
        taskId = Task.Id(taskInfo.getTaskId),
        runSpecVersion = version,
        status = Task.Status(
          stagedAt = now,
          condition = taskCondition,
          networkInfo = NetworkInfo(hostName = "host.some", hostPorts = Seq(1, 2, 3), ipAddresses = Nil)))
    }

    def minimalTask(appId: PathId): Task = minimalTask(Instance.Id.forRunSpec(appId), None, Timestamp.now())

    def minimalTask(instanceId: Instance.Id, container: Option[MesosContainer], now: Timestamp): Task =
      minimalTask(Task.Id.forInstanceId(instanceId, container), now)

    def minimalTask(taskId: Task.Id, now: Timestamp = Timestamp.now(),
      mesosStatus: Option[mesos.Protos.TaskStatus] = None): Task = {
      minimalTask(taskId, now, mesosStatus,
        if (mesosStatus.isDefined) TaskCondition(mesosStatus.get) else Condition.Provisioned)
    }

    def minimalTask(taskId: Task.Id, now: Timestamp, mesosStatus: Option[mesos.Protos.TaskStatus],
      taskCondition: Condition): Task = {
      Task(
        taskId,
        runSpecVersion = now,
        status = Task.Status(
          stagedAt = now,
          startedAt = None,
          mesosStatus = mesosStatus,
          condition = taskCondition,
          networkInfo = NetworkInfo("host.some", hostPorts = Nil, ipAddresses = Nil)))
    }

    def minimalLostTask(appId: PathId, taskCondition: Condition = Condition.Gone,
      since: Timestamp = Timestamp.now()): Task = {
      val instanceId = Instance.Id.forRunSpec(appId)
      val taskId = Task.Id.forInstanceId(instanceId)
      val status = MesosTaskStatusTestHelper.lost(
        mesos.Protos.TaskStatus.Reason.REASON_RECONCILIATION, taskId, since)
      minimalTask(
        taskId = taskId,
        now = since,
        mesosStatus = Some(status),
        taskCondition = taskCondition
      )
    }

    def minimalUnreachableTask(appId: PathId, taskCondition: Condition = Condition.Unreachable,
      since: Timestamp = Timestamp.now()): Task = {
      val lostTask = minimalLostTask(appId = appId, since = since)
      val mesosStatus = MesosTaskStatusTestHelper.unreachable(taskId = lostTask.taskId, since = since)
      val status = lostTask.status.copy(condition = taskCondition, mesosStatus = Some(mesosStatus))
      lostTask.copy(status = status)
    }

    def minimalRunning(appId: PathId, taskCondition: Condition = Condition.Running,
      since: Timestamp = Timestamp.now()): Task = {
      val instanceId = Instance.Id.forRunSpec(appId)
      val taskId = Task.Id.forInstanceId(instanceId)
      val status = MesosTaskStatusTestHelper.mesosStatus(
        state = mesos.Protos.TaskState.TASK_RUNNING, maybeHealthy = Option(true), taskId = taskId)
      minimalTask(
        taskId = taskId,
        now = since,
        mesosStatus = Some(status),
        taskCondition = taskCondition
      )
    }

    def residentReservedTask(appId: PathId, maybeTaskId: Option[Task.Id] = None): Task = {
      val instanceId = Instance.Id.forRunSpec(appId)
      val taskId = maybeTaskId.getOrElse(Task.Id.forInstanceId(instanceId))
      Task(
        taskId = taskId,
        status = Task.Status(Timestamp.now(), condition = Condition.Reserved, networkInfo = NetworkInfoPlaceholder()),
        runSpecVersion = Timestamp.now())
    }

    def residentLaunchedTask(appId: PathId, maybeTaskId: Option[Task.Id] = None): Task = {
      val now = Timestamp.now()
      val instanceId = Instance.Id.forRunSpec(appId)
      val taskId = maybeTaskId.getOrElse(Task.Id.forInstanceId(instanceId))
      Task(
        taskId = taskId,
        runSpecVersion = now,
        status = Task.Status(
          stagedAt = now,
          startedAt = Some(now),
          mesosStatus = None,
          condition = Condition.Running,
          networkInfo = NetworkInfoPlaceholder()))
    }

    def unreachableTask(appId: PathId, maybeTaskId: Option[Task.Id] = None): Task = {
      val now = Timestamp.now()
      val instanceId = Instance.Id.forRunSpec(appId)
      val taskId = maybeTaskId.getOrElse(Task.Id.forInstanceId(instanceId))
      Task(
        taskId = taskId,
        runSpecVersion = now,
        status = Task.Status(
          stagedAt = now,
          startedAt = Some(now),
          mesosStatus = None,
          condition = Condition.Unreachable,
          networkInfo = NetworkInfoPlaceholder()))
    }

    def startingTaskForApp(instanceId: Instance.Id, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2,
      container: Option[MesosContainer] = None): Task =
      startingTask(
        Task.Id.forInstanceId(instanceId, container),
        appVersion = appVersion,
        stagedAt = stagedAt)

    def startingTask(taskId: Task.Id, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Task =
      Task(
        taskId = taskId,
        runSpecVersion = appVersion,
        status = Task.Status(
          stagedAt = Timestamp(stagedAt),
          startedAt = None,
          mesosStatus = Some(statusForState(taskId.idString, mesos.Protos.TaskState.TASK_STARTING)),
          condition = Condition.Starting,
          networkInfo = NetworkInfoPlaceholder()))

    def stagedTaskForApp(
      appId: PathId = PathId("/test"), appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Task = {
      val instanceId = Instance.Id.forRunSpec(appId)
      stagedTask(instanceId, appVersion = appVersion, stagedAt = stagedAt)
    }

    def stagedTask(instanceId: Instance.Id, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2, container: Option[MesosContainer] = None): Task = {
      val taskId = Task.Id.forInstanceId(instanceId, container)

      Task(
        taskId = taskId,
        runSpecVersion = appVersion,
        status = Task.Status(
          stagedAt = Timestamp(stagedAt),
          startedAt = None,
          mesosStatus = Some(statusForState(taskId.idString, mesos.Protos.TaskState.TASK_STAGING)),
          condition = Condition.Staging,
          networkInfo = NetworkInfoPlaceholder()))
    }

    def statusForState(taskId: String, state: mesos.Protos.TaskState,
      maybeReason: Option[mesos.Protos.TaskStatus.Reason] = None): mesos.Protos.TaskStatus = {
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
      startedAt: Long = 3): Task =
      runningTask(
        Instance.Id.forRunSpec(appId),
        appVersion = appVersion,
        stagedAt = stagedAt,
        startedAt = startedAt
      )

    def runningTask(
      instanceId: Instance.Id,
      appVersion: Timestamp = Timestamp(1),
      stagedAt: Long = 2,
      startedAt: Long = 3,
      container: Option[MesosContainer] = None): Task = {
      import mesosphere.marathon.test.MarathonTestHelper.Implicits._

      val taskId = Task.Id.forInstanceId(instanceId, container)
      startingTask(taskId, appVersion, stagedAt)
        .withStatus((status: Task.Status) =>
          status.copy(
            startedAt = Some(Timestamp(startedAt)),
            mesosStatus = Some(statusForState(taskId.idString, mesos.Protos.TaskState.TASK_RUNNING)),
            condition = Condition.Running))
    }

    def killedTask(
      taskId: Task.Id,
      appVersion: Timestamp = Timestamp(1),
      stagedAt: Long = 2,
      startedAt: Long = 3): Task = {

      startingTask(taskId, appVersion, stagedAt)
        .withStatus((status: Task.Status) =>
          status.copy(
            condition = Condition.Killed,
            startedAt = Some(Timestamp(startedAt)),
            mesosStatus = Some(statusForState(taskId.idString, mesos.Protos.TaskState.TASK_KILLED))))
    }

    def healthyTask(instanceId: Instance.Id): Task = {
      import mesosphere.marathon.test.MarathonTestHelper.Implicits._

      runningTask(instanceId).withStatus { status =>
        status.copy(mesosStatus = status.mesosStatus.map(_.toBuilder.setHealthy(true).build()))
      }
    }

    def unhealthyTask(appId: PathId): Task = unhealthyTask(Instance.Id.forRunSpec(appId))

    def unhealthyTask(instanceId: Instance.Id): Task = {
      import mesosphere.marathon.test.MarathonTestHelper.Implicits._

      runningTask(instanceId).withStatus { status =>
        status.copy(mesosStatus = status.mesosStatus.map(_.toBuilder.setHealthy(false).build()))
      }
    }
  }

}
