package mesosphere.marathon

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import com.github.fge.jackson.JsonLoader
import com.github.fge.jsonschema.core.report.ProcessingReport
import com.github.fge.jsonschema.main.JsonSchemaFactory
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.api.v2.json.V2AppDefinition
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ PathId, TaskRepository, AppDefinition, MarathonStore, MarathonTaskState, Timestamp }
import mesosphere.marathon.tasks._
import mesosphere.mesos.protos._
import org.apache.mesos.Protos.{ CommandInfo, TaskID, TaskInfo, Offer }
import mesosphere.util.state.PersistentStore
import mesosphere.util.state.memory.InMemoryStore
import play.api.libs.json.Json

trait MarathonTestHelper {

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
      "--min_revive_offers_interval", minReviveOffersInterval.toString
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
                           version: Timestamp = Timestamp(10), now: Timestamp = Timestamp(10)): MarathonTask =
    {
      import scala.collection.JavaConverters._
      MarathonTasks.makeTask(
        id = taskInfo.getTaskId.getValue,
        host = offer.getHostname,
        ports = Seq(1, 2, 3), // doesn't matter here
        attributes = offer.getAttributesList.asScala,
        version = version,
        now = now,
        slaveId = offer.getSlaveId
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

  def validateJsonSchema(app: V2AppDefinition, valid: Boolean = true) {
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

  def createTaskTracker(
    store: PersistentStore = new InMemoryStore,
    config: MarathonConf = defaultConfig(),
    metrics: Metrics = new Metrics(new MetricRegistry)): TaskTrackerImpl = {

    val metrics = new Metrics(new MetricRegistry)
    val taskRepo = new TaskRepository(
      new MarathonStore[MarathonTaskState](
        store = store,
        metrics = metrics,
        newState = () => MarathonTaskState(MarathonTask.newBuilder().setId(UUID.randomUUID().toString).build()),
        prefix = TaskRepository.storePrefix),
      metrics
    )

    new TaskTrackerImpl(taskRepo, defaultConfig())
  }

  def dummyTask(appId: PathId) = MarathonTask.newBuilder()
    .setId(TaskIdUtil.newTaskId(appId).getValue)
    .build()
}

object MarathonTestHelper extends MarathonTestHelper
