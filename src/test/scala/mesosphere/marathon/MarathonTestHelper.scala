package mesosphere.marathon

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import com.github.fge.jackson.JsonLoader
import com.github.fge.jsonschema.core.report.ProcessingReport
import com.github.fge.jsonschema.main.JsonSchemaFactory
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.core.task.tracker.{ TaskTracker, TaskTrackerModule }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, MarathonStore, MarathonTaskState, PathId, TaskRepository, Timestamp }
import mesosphere.mesos.protos.{ FrameworkID, OfferID, Range, RangesResource, Resource, ScalarResource, SlaveID }
import mesosphere.util.state.PersistentStore
import mesosphere.util.state.memory.InMemoryStore
import org.apache.mesos.Protos.{ CommandInfo, Offer, TaskID, TaskInfo }
import org.apache.mesos.{ Protos => MesosProtos }
import play.api.libs.json.Json

import scala.util.Random

object MarathonTestHelper {

  import mesosphere.mesos.protos.Implicits._

  def makeConfig(args: String*): AllConf = {
    val opts = new AllConf(args) {
      // scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
    }
    opts.afterInit()
    opts
  }

  def defaultConfig(
    maxTasksPerOffer: Int = 1,
    minReviveOffersInterval: Long = 100,
    mesosRole: Option[String] = None,
    acceptedResourceRoles: Option[Set[String]] = None,
    envVarsPrefix: Option[String] = None): AllConf = {

    var args = Seq(
      "--master", "127.0.0.1:5050",
      "--max_tasks_per_offer", maxTasksPerOffer.toString,
      "--min_revive_offers_interval", minReviveOffersInterval.toString,
      "--mesos_authentication_principal", "marathon"
    )

    mesosRole.foreach(args ++= Seq("--mesos_role", _))
    acceptedResourceRoles.foreach(v => args ++= Seq("--default_accepted_resource_roles", v.mkString(",")))
    envVarsPrefix.foreach(args ++ Seq("--env_vars_prefix", _))
    makeConfig(args: _*)
  }

  def makeBasicOffer(cpus: Double = 4.0, mem: Double = 16000, disk: Double = 1.0,
                     beginPort: Int = 31000, endPort: Int = 32000, role: String = "*"): Offer.Builder = {
    val cpusResource = ScalarResource(Resource.CPUS, cpus, role = role)
    val memResource = ScalarResource(Resource.MEM, mem, role = role)
    val diskResource = ScalarResource(Resource.DISK, disk, role = role)
    val portsResource = if (beginPort <= endPort) {
      Some(RangesResource(
        Resource.PORTS,
        Seq(Range(beginPort.toLong, endPort.toLong)),
        role
      ))
    }
    else {
      None
    }
    val offerBuilder = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(FrameworkID("marathon"))
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)

    portsResource.foreach(offerBuilder.addResources(_))

    offerBuilder
  }

  /**
    * @param ranges how many port ranges should be included in this offer
    * @return
    */
  def makeBasicOfferWithManyPortRanges(ranges: Int): Offer.Builder = {
    val role = "*"
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
      .setFrameworkId(FrameworkID("marathon"))
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
      .setFrameworkId(FrameworkID("marathon"))
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
      .addResources(ScalarResource(Resource.CPUS, 1.0, "*"))
  }

  def makeTaskFromTaskInfo(taskInfo: TaskInfo,
                           offer: Offer = makeBasicOffer().build(),
                           version: Timestamp = Timestamp(10), now: Timestamp = Timestamp(10)): Task =
    {
      import scala.collection.JavaConverters._

      Task(
        taskId = Task.Id(taskInfo.getTaskId),
        agentInfo = Task.AgentInfo(
          host = offer.getHostname,
          agentId = Some(offer.getSlaveId.getValue),
          attributes = offer.getAttributesList.asScala
        ),
        launched = Some(
          Task.Launched(
            appVersion = version,
            status = Task.Status(
              stagedAt = now
            ),
            networking = Task.HostPorts(Seq(1, 2, 3))
          )
        )
      )
    }

  def makeBasicApp() = AppDefinition(
    id = "test-app".toPath,
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

    new TaskTrackerModule(Clock(), metrics, defaultConfig(), leadershipModule, taskRepo) {
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

  def dummyTaskBuilder(appId: PathId) = MarathonTask.newBuilder()
    .setId(Task.Id.forApp(appId).idString)
    .setHost("host.some")

  def dummyTaskProto(appId: PathId) = dummyTaskBuilder(appId).build()
  def dummyTaskProto(taskId: String) = MarathonTask.newBuilder()
    .setId(taskId)
    .setHost("host.some")
    .build()

  def mininimalTask(appId: PathId): Task = mininimalTask(Task.Id.forApp(appId).idString)
  def mininimalTask(taskId: Task.Id): Task = mininimalTask(taskId.idString)
  def mininimalTask(taskId: String): Task = {
    Task(
      Task.Id(taskId),
      Task.AgentInfo(host = "host.some", agentId = None, attributes = Iterable.empty),
      reservationWithVolumes = None,
      launched = None
    )
  }

  def startingTaskForApp(appId: PathId, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Task =
    startingTask(
      Task.Id.forApp(appId).idString,
      appVersion = appVersion,
      stagedAt = stagedAt
    )
  def startingTask(taskId: String, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Task =
    TaskSerializer.fromProto(
      startingTaskProto(taskId, appVersion = appVersion, stagedAt = stagedAt)
    )

  def startingTaskProto(appId: PathId): Protos.MarathonTask = startingTaskProto(Task.Id.forApp(appId).idString)
  def startingTaskProto(taskId: String, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Protos.MarathonTask = {
    dummyTaskProto(taskId).toBuilder
      .setVersion(appVersion.toString)
      .setStagedAt(stagedAt)
      .setStatus(statusForState(taskId, MesosProtos.TaskState.TASK_STARTING))
      .build()
  }

  def stagedTaskForApp(
    appId: PathId = PathId("/test"), appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Task =
    stagedTask(Task.Id.forApp(appId).idString, appVersion = appVersion, stagedAt = stagedAt)
  def stagedTask(taskId: String, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Task =
    TaskSerializer.fromProto(stagedTaskProto(taskId, appVersion = appVersion, stagedAt = stagedAt))

  def stagedTaskProto(appId: PathId): Protos.MarathonTask = stagedTaskProto(Task.Id.forApp(appId).idString)
  def stagedTaskProto(taskId: String, appVersion: Timestamp = Timestamp(1), stagedAt: Long = 2): Protos.MarathonTask = {
    startingTaskProto(taskId, appVersion = appVersion, stagedAt = stagedAt).toBuilder
      .setStatus(statusForState(taskId, MesosProtos.TaskState.TASK_STAGING))
      .build()
  }

  def runningTaskForApp(appId: PathId = PathId("/test"),
                        appVersion: Timestamp = Timestamp(1),
                        stagedAt: Long = 2,
                        startedAt: Long = 3): Task =
    runningTask(
      Task.Id.forApp(appId).idString,
      appVersion = appVersion,
      stagedAt = stagedAt,
      startedAt = startedAt
    )
  def runningTask(
    taskId: String,
    appVersion: Timestamp = Timestamp(1),
    stagedAt: Long = 2,
    startedAt: Long = 3): Task =
    TaskSerializer.fromProto(
      runningTaskProto(
        taskId,
        appVersion = appVersion,
        stagedAt = stagedAt,
        startedAt = startedAt
      )
    )

  def runningTaskProto(appId: PathId): Protos.MarathonTask = runningTaskProto(Task.Id.forApp(appId).idString)
  def runningTaskProto(
    taskId: String,
    appVersion: Timestamp = Timestamp(1),
    stagedAt: Long = 2,
    startedAt: Long = 3): Protos.MarathonTask = {
    stagedTaskProto(taskId, appVersion = appVersion, stagedAt = stagedAt).toBuilder
      .setStartedAt(startedAt)
      .setStatus(statusForState(taskId, MesosProtos.TaskState.TASK_RUNNING))
      .build()
  }

  def healthyTask(appId: PathId): Task = healthyTask(Task.Id.forApp(appId).idString)
  def healthyTask(taskId: String): Task = TaskSerializer.fromProto(healthyTaskProto(taskId))

  def healthyTaskProto(appId: PathId): Protos.MarathonTask = healthyTaskProto(Task.Id.forApp(appId).idString)
  def healthyTaskProto(taskId: String): Protos.MarathonTask = {
    val task: MarathonTask = runningTaskProto(taskId)
    task.toBuilder
      .setStatus(task.getStatus.toBuilder.setHealthy(true))
      .buildPartial()
  }

  def unhealthyTask(appId: PathId): Task = unhealthyTask(Task.Id.forApp(appId).idString)
  def unhealthyTask(taskId: String): Task = TaskSerializer.fromProto(unhealthyTaskProto(taskId))

  def unhealthyTaskProto(appId: PathId): Protos.MarathonTask = unhealthyTaskProto(Task.Id.forApp(appId).idString)
  def unhealthyTaskProto(taskId: String): Protos.MarathonTask = {
    val task: MarathonTask = runningTaskProto(taskId)
    task.toBuilder
      .setStatus(task.getStatus.toBuilder.setHealthy(false))
      .buildPartial()
  }

  private[this] def statusForState(taskId: String, state: MesosProtos.TaskState): MesosProtos.TaskStatus = {
    MesosProtos.TaskStatus
      .newBuilder()
      .setTaskId(TaskID.newBuilder().setValue(taskId))
      .setState(state)
      .buildPartial()
  }

}
