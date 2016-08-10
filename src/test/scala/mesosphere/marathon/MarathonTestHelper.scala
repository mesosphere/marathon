package mesosphere.marathon

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import com.github.fge.jackson.JsonLoader
import com.github.fge.jsonschema.core.report.ProcessingReport
import com.github.fge.jsonschema.main.JsonSchemaFactory
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.api.serialization.LabelsSerializer
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.impl.{ ReservationLabels, TaskLabels }
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import mesosphere.marathon.core.task.update.TaskUpdateStep
import mesosphere.marathon.core.task.{ Task, TaskStateOp }
import mesosphere.marathon.core.task.tracker.{ TaskTracker, TaskTrackerModule }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.mesos.protos.{ FrameworkID, OfferID, Range, RangesResource, Resource, ScalarResource, SlaveID }
import mesosphere.util.state.{ FrameworkId, PersistentStore }
import mesosphere.util.state.memory.InMemoryStore
import org.apache.mesos.Protos.Resource.{ DiskInfo, ReservationInfo }
import org.apache.mesos.Protos._
import org.apache.mesos.{ Protos => Mesos }
import play.api.libs.json.Json

import scala.collection.JavaConverters
import scala.collection.immutable.Seq
import scala.util.Random

//scalastyle:off number.of.methods

object MarathonTestHelper {

  import mesosphere.mesos.protos.Implicits._

  lazy val clock = Clock()

  def makeConfig(args: String*): AllConf = {
    new AllConf(args) {
      // scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
      verify()
    }
  }

  def defaultConfig(
    maxTasksPerOffer: Int = 1,
    minReviveOffersInterval: Long = 100,
    mesosRole: Option[String] = None,
    acceptedResourceRoles: Option[Set[String]] = None,
    envVarsPrefix: Option[String] = None,
    principal: Option[String] = None,
    maxZkNodeSize: Option[Int] = None): AllConf = {

    var args = Seq(
      "--master", "127.0.0.1:5050",
      "--max_tasks_per_offer", maxTasksPerOffer.toString,
      "--min_revive_offers_interval", minReviveOffersInterval.toString,
      "--mesos_authentication_principal", "marathon"
    )

    mesosRole.foreach(args ++= Seq("--mesos_role", _))
    acceptedResourceRoles.foreach(v => args ++= Seq("--default_accepted_resource_roles", v.mkString(",")))
    maxZkNodeSize.foreach(size => args ++= Seq("--zk_max_node_size", size.toString))
    envVarsPrefix.foreach(args ++= Seq("--env_vars_prefix", _))
    makeConfig(args: _*)
  }

  val frameworkID: FrameworkID = FrameworkID("marathon")
  val frameworkId: FrameworkId = FrameworkId("").mergeFromProto(frameworkID)

  def makeBasicOffer(cpus: Double = 4.0, mem: Double = 16000, disk: Double = 1.0,
    beginPort: Int = 31000, endPort: Int = 32000, role: String = ResourceRole.Unreserved,
    reservation: Option[ReservationLabels] = None): Offer.Builder = {

    require(role != ResourceRole.Unreserved || reservation.isEmpty, "reserved resources cannot have role *")

    def heedReserved(resource: Mesos.Resource): Mesos.Resource = {
      reservation match {
        case Some(reservationWithLabels) =>
          val labels = reservationWithLabels.mesosLabels
          val reservation =
            Mesos.Resource.ReservationInfo.newBuilder()
              .setPrincipal("marathon")
              .setLabels(labels)
          resource.toBuilder.setReservation(reservation).build()
        case None =>
          resource
      }
    }

    val cpusResource = heedReserved(ScalarResource(Resource.CPUS, cpus, role = role))
    val memResource = heedReserved(ScalarResource(Resource.MEM, mem, role = role))
    val diskResource = heedReserved(ScalarResource(Resource.DISK, disk, role = role))
    val portsResource = if (beginPort <= endPort) {
      Some(heedReserved(RangesResource(
        Resource.PORTS,
        Seq(Range(beginPort.toLong, endPort.toLong)),
        role
      )))
    } else {
      None
    }
    val offerBuilder = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(frameworkID)
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)

    portsResource.foreach(offerBuilder.addResources(_))

    offerBuilder
  }

  def scalarResource(
    name: String, d: Double, role: String = ResourceRole.Unreserved,
    reservation: Option[ReservationInfo] = None, disk: Option[DiskInfo] = None): Mesos.Resource = {

    val builder = Mesos.Resource
      .newBuilder()
      .setName(name)
      .setType(Value.Type.SCALAR)
      .setScalar(Value.Scalar.newBuilder().setValue(d))
      .setRole(role)

    reservation.foreach(builder.setReservation)
    disk.foreach(builder.setDisk)

    builder.build()
  }

  def portsResource(
    begin: Long, end: Long, role: String = ResourceRole.Unreserved,
    reservation: Option[ReservationInfo] = None): Mesos.Resource = {

    val ranges = Mesos.Value.Ranges.newBuilder()
      .addRange(Mesos.Value.Range.newBuilder().setBegin(begin).setEnd(end))

    val builder = Mesos.Resource
      .newBuilder()
      .setName(Resource.PORTS)
      .setType(Value.Type.RANGES)
      .setRanges(ranges)
      .setRole(role)

    reservation.foreach(builder.setReservation)

    builder.build()
  }

  def reservation(principal: String, labels: Map[String, String] = Map.empty): Mesos.Resource.ReservationInfo = {
    Mesos.Resource.ReservationInfo.newBuilder()
      .setPrincipal(principal)
      .setLabels(LabelsSerializer.toMesosLabelsBuilder(labels))
      .build()
  }

  def reservedDisk(id: String, size: Double = 4096, role: String = ResourceRole.Unreserved,
    principal: String = "test", containerPath: String = "/container"): Mesos.Resource.Builder = {
    import Mesos.Resource.{ DiskInfo, ReservationInfo }
    Mesos.Resource.newBuilder()
      .setType(Mesos.Value.Type.SCALAR)
      .setName(Resource.DISK)
      .setScalar(Mesos.Value.Scalar.newBuilder.setValue(size))
      .setRole(role)
      .setReservation(ReservationInfo.newBuilder().setPrincipal(principal))
      .setDisk(DiskInfo.newBuilder()
        .setPersistence(DiskInfo.Persistence.newBuilder().setId(id))
        .setVolume(Mesos.Volume.newBuilder()
          .setMode(Mesos.Volume.Mode.RW)
          .setContainerPath(containerPath)
        )
      )
  }

  /**
    * @param ranges how many port ranges should be included in this offer
    * @return
    */
  def makeBasicOfferWithManyPortRanges(ranges: Int): Offer.Builder = {
    val role = ResourceRole.Unreserved
    val cpusResource = ScalarResource(Resource.CPUS, 4.0, role = role)
    val memResource = ScalarResource(Resource.MEM, 16000, role = role)
    val diskResource = ScalarResource(Resource.DISK, 1.0, role = role)
    val portsResource = RangesResource(
      Resource.PORTS,
      List.tabulate(ranges)(_ * 2 + 1).map(p => Range(p.toLong, (p + 1).toLong)),
      role
    )

    val offerBuilder = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(frameworkID)
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addResources(portsResource)

    offerBuilder
  }

  def makeBasicOfferWithRole(cpus: Double, mem: Double, disk: Double,
    beginPort: Int, endPort: Int, role: String) = {
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(Range(beginPort.toLong, endPort.toLong)),
      role
    )
    val cpusResource = ScalarResource(Resource.CPUS, cpus, role)
    val memResource = ScalarResource(Resource.MEM, mem, role)
    val diskResource = ScalarResource(Resource.DISK, disk, role)
    Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(frameworkID)
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addResources(portsResource)
  }

  def makeOneCPUTask(taskId: String): TaskInfo.Builder = {
    TaskInfo.newBuilder()
      .setName("true")
      .setTaskId(TaskID.newBuilder().setValue(taskId).build())
      .setSlaveId(SlaveID("slave1"))
      .setCommand(CommandInfo.newBuilder().setShell(true).addArguments("true"))
      .addResources(ScalarResource(Resource.CPUS, 1.0, ResourceRole.Unreserved))
  }

  def makeTaskFromTaskInfo(
    taskInfo: TaskInfo,
    offer: Offer = makeBasicOffer().build(),
    version: Timestamp = Timestamp(10), now: Timestamp = Timestamp(10),
    marathonTaskStatus: MarathonTaskStatus = MarathonTaskStatus.Staging): Task.LaunchedEphemeral =
    {
      import scala.collection.JavaConverters._

      Task.LaunchedEphemeral(
        taskId = Task.Id(taskInfo.getTaskId),
        agentInfo = Task.AgentInfo(
          host = offer.getHostname,
          agentId = Some(offer.getSlaveId.getValue),
          attributes = offer.getAttributesList.asScala
        ),
        runSpecVersion = version,
        status = Task.Status(
          stagedAt = now,
          taskStatus = marathonTaskStatus
        ),
        hostPorts = Seq(1, 2, 3)
      )
    }

  def makeBasicApp() = AppDefinition(
    id = "/test-app".toPath,
    cpus = 1.0,
    mem = 64.0,
    disk = 1.0,
    executor = "//cmd"
  )

  lazy val appSchema = {
    val appJson = "/public/api/v2/schema/AppDefinition.json"
    val appDefinition = JsonLoader.fromResource(appJson)
    val factory = JsonSchemaFactory.byDefault()
    factory.getJsonSchema(appDefinition)
  }

  def validateJsonSchema(app: AppDefinition, valid: Boolean = true) {
    import mesosphere.marathon.api.v2.json.Formats._
    // TODO: Revalidate the decision to disallow null values in schema
    // Possible resolution: Do not render null values in our formats by default anymore.
    val appStr = Json.prettyPrint(JsonTestHelper.removeNullFieldValues(Json.toJson(app)))
    validateJsonSchemaForString(appStr, valid)
  }

  def validateJsonSchemaForString(appStr: String, valid: Boolean): Unit = {
    val appJson = JsonLoader.fromString(appStr)
    val validationResult: ProcessingReport = appSchema.validate(appJson)
    lazy val pretty = Json.prettyPrint(Json.parse(appStr))
    assert(validationResult.isSuccess == valid, s"validation errors $validationResult for json:\n$pretty")
  }

  def createTaskTrackerModule(
    leadershipModule: LeadershipModule,
    store: PersistentStore = new InMemoryStore,
    config: MarathonConf = defaultConfig(),
    metrics: Metrics = new Metrics(new MetricRegistry)): TaskTrackerModule = {

    val metrics = new Metrics(new MetricRegistry)
    val taskRepo = new TaskRepository(
      new MarathonStore[MarathonTaskState](
        store = store,
        metrics = metrics,
        newState = () => MarathonTaskState(MarathonTask.newBuilder().setId(UUID.randomUUID().toString).build()),
        prefix = TaskRepository.storePrefix),
      metrics
    )
    val updateSteps = Seq.empty[TaskUpdateStep]

    new TaskTrackerModule(clock, metrics, defaultConfig(), leadershipModule, taskRepo, updateSteps) {
      // some tests create only one actor system but create multiple task trackers
      override protected lazy val taskTrackerActorName: String = s"taskTracker_${Random.alphanumeric.take(10).mkString}"
    }
  }

  def createTaskTracker(
    leadershipModule: LeadershipModule,
    store: PersistentStore = new InMemoryStore,
    config: MarathonConf = defaultConfig(),
    metrics: Metrics = new Metrics(new MetricRegistry)): TaskTracker = {
    createTaskTrackerModule(leadershipModule, store, config, metrics).taskTracker
  }

  def mininimalTask(appId: PathId): Task.LaunchedEphemeral = mininimalTask(Task.Id.forRunSpec(appId).idString)
  def mininimalTask(taskId: Task.Id): Task.LaunchedEphemeral = mininimalTask(taskId.idString)
  def mininimalTask(taskId: String, now: Timestamp = clock.now(), mesosStatus: Option[TaskStatus] = None): Task.LaunchedEphemeral = {
    mininimalTask(taskId, now, mesosStatus, if (mesosStatus.isDefined) MarathonTaskStatus(mesosStatus.get) else MarathonTaskStatus.Created)
  }
  def mininimalTask(taskId: String, now: Timestamp, mesosStatus: Option[TaskStatus], marathonTaskStatus: MarathonTaskStatus): Task.LaunchedEphemeral = {
    Task.LaunchedEphemeral(
      Task.Id(taskId),
      Task.AgentInfo(host = "host.some", agentId = None, attributes = Iterable.empty),
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

  def mininimalLostTask(appId: PathId, marathonTaskStatus: MarathonTaskStatus = MarathonTaskStatus.Gone, since: Timestamp = clock.now()): Task.LaunchedEphemeral = {
    val taskId = Task.Id.forRunSpec(appId)
    val status = TaskStatusUpdateTestHelper.makeMesosTaskStatus(taskId, TaskState.TASK_LOST, maybeReason = Some(TaskStatus.Reason.REASON_RECONCILIATION))
    mininimalTask(
      taskId = taskId.idString,
      now = since,
      mesosStatus = Some(status),
      marathonTaskStatus = marathonTaskStatus
    )
  }

  def minimalUnreachableTask(appId: PathId, marathonTaskStatus: MarathonTaskStatus = MarathonTaskStatus.Unreachable, since: Timestamp = clock.now()): Task.LaunchedEphemeral = {
    val lostTask = mininimalLostTask(appId, since = since)
    lostTask.copy(status = lostTask.status.copy(taskStatus = marathonTaskStatus))
  }

  def minimalRunning(appId: PathId, marathonTaskStatus: MarathonTaskStatus = MarathonTaskStatus.Running, since: Timestamp = clock.now()): Task.LaunchedEphemeral = {
    val taskId = Task.Id.forRunSpec(appId)
    val status = TaskStatusUpdateTestHelper.makeMesosTaskStatus(taskId, TaskState.TASK_RUNNING, maybeHealth = Option(true))
    mininimalTask(
      taskId = taskId.idString,
      now = since,
      mesosStatus = Some(status),
      marathonTaskStatus = marathonTaskStatus
    )
  }

  def minimalReservedTask(appId: PathId, reservation: Task.Reservation): Task.Reserved =
    Task.Reserved(
      taskId = Task.Id.forRunSpec(appId),
      Task.AgentInfo(host = "host.some", agentId = None, attributes = Iterable.empty),
      reservation = reservation,
      status = Task.Status(Timestamp.now(), taskStatus = MarathonTaskStatus.Reserved))

  def newReservation: Task.Reservation = Task.Reservation(Seq.empty, taskReservationStateNew)

  def taskReservationStateNew = Task.Reservation.State.New(timeout = None)

  def taskLaunched: Task.Launched = {
    val now = Timestamp.now()
    Task.Launched(now, status = Task.Status(stagedAt = now, taskStatus = MarathonTaskStatus.Running), hostPorts = Seq.empty)
  }

  def taskLaunchedOp(taskId: Task.Id): TaskStateOp.LaunchOnReservation = {
    val now = Timestamp.now()
    TaskStateOp.LaunchOnReservation(taskId = taskId, runSpecVersion = now, status = Task.Status(stagedAt = now, taskStatus = MarathonTaskStatus.Running), hostPorts = Seq.empty)
  }

  def startingTaskForApp(appId: PathId, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Task.LaunchedEphemeral =
    startingTask(
      Task.Id.forRunSpec(appId).idString,
      appVersion = appVersion,
      stagedAt = stagedAt
    )
  def startingTask(taskId: String, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Task.LaunchedEphemeral =
    Task.LaunchedEphemeral(
      taskId = Task.Id(taskId),
      agentInfo = Task.AgentInfo("some.host", Some("agent-1"), Iterable.empty),
      runSpecVersion = appVersion,
      status = Task.Status(
        stagedAt = Timestamp(stagedAt),
        startedAt = None,
        mesosStatus = Some(statusForState(taskId, Mesos.TaskState.TASK_STARTING)),
        taskStatus = MarathonTaskStatus.Starting
      ),
      hostPorts = Seq.empty
    )

  def stagedTaskForApp(
    appId: PathId = PathId("/test"), appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Task.LaunchedEphemeral =
    stagedTask(Task.Id.forRunSpec(appId).idString, appVersion = appVersion, stagedAt = stagedAt)
  def stagedTask(
    taskId: String,
    appVersion: Timestamp = Timestamp(1),
    stagedAt: Long = 2,
    mesosStatus: Option[Mesos.TaskStatus] = None): Task.LaunchedEphemeral =
    Task.LaunchedEphemeral(
      taskId = Task.Id(taskId),
      agentInfo = Task.AgentInfo("some.host", Some("agent-1"), Iterable.empty),
      runSpecVersion = appVersion,
      status = Task.Status(
        stagedAt = Timestamp(stagedAt),
        startedAt = None,
        mesosStatus = Some(statusForState(taskId, Mesos.TaskState.TASK_STAGING)),
        taskStatus = MarathonTaskStatus.Staging
      ),
      hostPorts = Seq.empty
    )

  def runningTaskForApp(
    appId: PathId = PathId("/test"),
    appVersion: Timestamp = Timestamp(1),
    stagedAt: Long = 2,
    startedAt: Long = 3): Task.LaunchedEphemeral =
    runningTask(
      Task.Id.forRunSpec(appId).idString,
      appVersion = appVersion,
      stagedAt = stagedAt,
      startedAt = startedAt
    )
  def runningTask(
    taskId: String,
    appVersion: Timestamp = Timestamp(1),
    stagedAt: Long = 2,
    startedAt: Long = 3): Task.LaunchedEphemeral = {
    import Implicits._

    startingTask(taskId, appVersion, stagedAt)
      .withStatus((status: Task.Status) =>
        status.copy(
          startedAt = Some(Timestamp(startedAt)),
          mesosStatus = Some(statusForState(taskId, Mesos.TaskState.TASK_RUNNING))
        )
      )

  }

  def healthyTask(appId: PathId): Task.LaunchedEphemeral = healthyTask(Task.Id.forRunSpec(appId).idString)
  def healthyTask(taskId: String): Task.LaunchedEphemeral = {
    import Implicits._
    runningTask(taskId).withStatus { status =>
      status.copy(mesosStatus = status.mesosStatus.map(_.toBuilder.setHealthy(true).build()))
    }
  }

  def unhealthyTask(appId: PathId): Task.LaunchedEphemeral = unhealthyTask(Task.Id.forRunSpec(appId).idString)
  def unhealthyTask(taskId: String): Task.LaunchedEphemeral = {
    import Implicits._
    runningTask(taskId).withStatus { status =>
      status.copy(mesosStatus = status.mesosStatus.map(_.toBuilder.setHealthy(false).build()))
    }
  }

  def lostTask(id: String, reason: TaskStatus.Reason): Protos.MarathonTask = {
    Protos.MarathonTask
      .newBuilder()
      .setId(id)
      .setStatus(statusForState(id, TaskState.TASK_LOST))
      .buildPartial()
  }

  def statusForState(taskId: String, state: Mesos.TaskState, maybeReason: Option[TaskStatus.Reason] = None): Mesos.TaskStatus = {
    val builder = Mesos.TaskStatus
      .newBuilder()
      .setTaskId(TaskID.newBuilder().setValue(taskId))
      .setState(state)

    maybeReason.foreach(builder.setReason)

    builder.buildPartial()
  }

  def persistentVolumeResources(taskId: Task.Id, localVolumeIds: Task.LocalVolumeId*) = localVolumeIds.map { id =>
    Mesos.Resource.newBuilder()
      .setName("disk")
      .setType(Mesos.Value.Type.SCALAR)
      .setScalar(Mesos.Value.Scalar.newBuilder().setValue(10))
      .setRole("test")
      .setReservation(
        Mesos.Resource.ReservationInfo
          .newBuilder()
          .setPrincipal("principal")
          .setLabels(TaskLabels.labelsForTask(frameworkId, taskId).mesosLabels)
      )
      .setDisk(Mesos.Resource.DiskInfo.newBuilder()
        .setPersistence(Mesos.Resource.DiskInfo.Persistence.newBuilder().setId(id.idString))
        .setVolume(Mesos.Volume.newBuilder()
          .setContainerPath(id.containerPath)
          .setMode(Mesos.Volume.Mode.RW)))
      .build()
  }

  def offerWithVolumes(taskId: String, localVolumeIds: Task.LocalVolumeId*) = {
    import scala.collection.JavaConverters._
    MarathonTestHelper.makeBasicOffer(
      reservation = Some(TaskLabels.labelsForTask(frameworkId, Task.Id(taskId))),
      role = "test"
    ).addAllResources(persistentVolumeResources(Task.Id(taskId), localVolumeIds: _*).asJava).build()
  }

  def offerWithVolumesOnly(taskId: Task.Id, localVolumeIds: Task.LocalVolumeId*) = {
    import scala.collection.JavaConverters._
    MarathonTestHelper.makeBasicOffer()
      .clearResources()
      .addAllResources(persistentVolumeResources(taskId, localVolumeIds: _*).asJava)
      .build()
  }

  def appWithPersistentVolume(): AppDefinition = {
    MarathonTestHelper.makeBasicApp().copy(
      container = Some(mesosContainerWithPersistentVolume),
      residency = Some(Residency(
        Residency.defaultRelaunchEscalationTimeoutSeconds,
        Residency.defaultTaskLostBehaviour))
    )
  }

  def residentReservedTask(appId: PathId, localVolumeIds: Task.LocalVolumeId*) =
    minimalReservedTask(appId, Task.Reservation(localVolumeIds, taskReservationStateNew))

  def residentLaunchedTask(appId: PathId, localVolumeIds: Task.LocalVolumeId*) = {
    val now = Timestamp.now()
    Task.LaunchedOnReservation(
      taskId = Task.Id.forRunSpec(appId),
      agentInfo = Task.AgentInfo(host = "host.some", agentId = None, attributes = Iterable.empty),
      runSpecVersion = now,
      status = Task.Status(
        stagedAt = now,
        startedAt = None,
        mesosStatus = None,
        taskStatus = MarathonTaskStatus.Running
      ),
      hostPorts = Seq.empty,
      reservation = Task.Reservation(localVolumeIds, Task.Reservation.State.Launched))
  }

  def mesosContainerWithPersistentVolume = Container.Mesos(
    volumes = Seq[mesosphere.marathon.state.Volume](
      PersistentVolume(
        containerPath = "persistent-volume",
        persistent = PersistentVolumeInfo(10), // must match persistentVolumeResources
        mode = Mesos.Volume.Mode.RW
      )
    )
  )

  def mesosIpAddress(ipAddress: String) = {
    Mesos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ipAddress).build
  }

  def networkInfoWithIPAddress(ipAddress: Mesos.NetworkInfo.IPAddress) = {
    Mesos.NetworkInfo.newBuilder().addIpAddresses(ipAddress).build
  }

  def containerStatusWithNetworkInfo(networkInfo: Mesos.NetworkInfo) = {
    Mesos.ContainerStatus.newBuilder().addNetworkInfos(networkInfo).build
  }

  object Implicits {
    implicit class AppDefinitionImprovements(app: AppDefinition) {
      def withPortDefinitions(portDefinitions: Seq[PortDefinition]): AppDefinition =
        app.copy(portDefinitions = portDefinitions)

      def withNoPortDefinitions(): AppDefinition = app.withPortDefinitions(Seq.empty)

      def withIpAddress(ipAddress: IpAddress): AppDefinition = app.copy(ipAddress = Some(ipAddress))

      def withDockerNetwork(network: Mesos.ContainerInfo.DockerInfo.Network): AppDefinition = {
        val docker = app.container.getOrElse(Container.Mesos()) match {
          case docker: Docker => docker
          case _ => Docker(image = "busybox")
        }

        app.copy(container = Some(docker.copy(network = Some(network))))
      }

      def withPortMappings(newPortMappings: Seq[PortMapping]): AppDefinition = {
        val container = app.container.getOrElse(Container.Mesos())
        val docker = container.docker.getOrElse(Docker(image = "busybox")).copy(portMappings = Some(newPortMappings))

        app.copy(container = Some(docker))
      }
    }

    implicit class TaskImprovements(task: Task) {
      def withAgentInfo(update: Task.AgentInfo => Task.AgentInfo): Task = task match {
        case launchedEphemeral: Task.LaunchedEphemeral =>
          launchedEphemeral.copy(agentInfo = update(launchedEphemeral.agentInfo))

        case reserved: Task.Reserved =>
          reserved.copy(agentInfo = update(reserved.agentInfo))

        case launchedOnReservation: Task.LaunchedOnReservation =>
          launchedOnReservation.copy(agentInfo = update(launchedOnReservation.agentInfo))
      }

      def withHostPorts(update: Seq[Int]): Task = task match {
        case launchedEphemeral: Task.LaunchedEphemeral => launchedEphemeral.copy(hostPorts = update)
        case launchedOnReservation: Task.LaunchedOnReservation => launchedOnReservation.copy(hostPorts = update)
        case reserved: Task.Reserved => throw new scala.RuntimeException("Reserved task cannot have hostPorts")
      }

      def withNetworkInfos(update: scala.collection.Seq[NetworkInfo]): Task = {
        def containerStatus(networkInfos: scala.collection.Seq[NetworkInfo]) = {
          import JavaConverters._
          Mesos.ContainerStatus.newBuilder().addAllNetworkInfos(networkInfos.asJava)
        }

        def mesosStatus(mesosStatus: Option[TaskStatus], networkInfos: scala.collection.Seq[NetworkInfo]): Option[TaskStatus] =
          Some(mesosStatus.getOrElse(Mesos.TaskStatus.getDefaultInstance).toBuilder
            .setContainerStatus(containerStatus(networkInfos))
            .build)

        task match {
          case launchedEphemeral: Task.LaunchedEphemeral =>
            val updatedStatus = launchedEphemeral.status.copy(mesosStatus = mesosStatus(launchedEphemeral.mesosStatus, update))
            launchedEphemeral.copy(status = updatedStatus)
          case launchedOnReservation: Task.LaunchedOnReservation =>
            val updatedStatus = launchedOnReservation.status.copy(mesosStatus = mesosStatus(launchedOnReservation.mesosStatus, update))
            launchedOnReservation.copy(status = updatedStatus)
          case reserved: Task.Reserved => throw new scala.RuntimeException("Reserved task cannot have status")
        }
      }

      def withStatus[T <: Task](update: Task.Status => Task.Status): T = task match {
        case launchedEphemeral: Task.LaunchedEphemeral =>
          launchedEphemeral.copy(status = update(launchedEphemeral.status)).asInstanceOf[T]

        case launchedOnReservation: Task.LaunchedOnReservation =>
          launchedOnReservation.copy(status = update(launchedOnReservation.status)).asInstanceOf[T]

        case reserved: Task.Reserved =>
          throw new scala.RuntimeException("Reserved task cannot have a status")
      }

    }
  }

}
