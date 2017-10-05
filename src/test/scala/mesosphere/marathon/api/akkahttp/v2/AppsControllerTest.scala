package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.model.{ HttpEntity, StatusCodes, Uri }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.marathon.api.{ JsonTestHelper, TestAuthFixture }
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.Health
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.readiness.{ HttpResponse, ReadinessCheckResult }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LaunchedEphemeral
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.raml.ReadinessCheck
import mesosphere.marathon.state.{ AppDefinition, Identifiable, TaskFailure, VersionInfo }
import mesosphere.marathon.test.SettableClock
import org.apache.mesos.{ Protos => mesos }

import scala.concurrent.Future
import scala.concurrent.duration._

class AppsControllerTest extends UnitTest with ScalatestRouteTest {

  case class Fixture(
      clock: SettableClock = new SettableClock(),
      auth: TestAuthFixture = new TestAuthFixture(),
      service: MarathonSchedulerService = mock[MarathonSchedulerService],
      appInfoService: AppInfoService = mock[AppInfoService],
      configArgs: Seq[String] = Seq("--enable_features", "external_volumes"),
      groupManager: GroupManager = mock[GroupManager]) {
    val config: AllConf = AllConf.withTestConfig(configArgs: _*)

    implicit val authenticator = auth.auth

    implicit val electionService: ElectionService = mock[ElectionService]

    electionService.isLeader returns true

    val appsController = new AppsController(
      clock = clock,
      eventBus = system.eventStream,
      service = service,
      appInfoService = appInfoService,
      config = config,
      groupManager = groupManager,
      pluginManager = PluginManager.None
    )
  }

  "accessing an app with authentication returns an app" in {

    Given("an app with full information")
    import mesosphere.marathon.state.PathId._

    val f = new Fixture()
    val appD = AppDefinition(id = "/a".toRootPath, versionInfo = VersionInfo.OnlyVersion(f.clock.now()))
    val taskCounts = TaskCounts(0, 3, 2, 1)
    val deployments = Seq(Identifiable("foo"), Identifiable("bar"))
    val readinessCheckResults = Seq(
      ReadinessCheckResult(name = "alice", taskId = Task.Id("marathon-task-1"), ready = true, lastResponse = None),
      ReadinessCheckResult(name = "bob", taskId = Task.Id("marathon-task-2"), ready = false, lastResponse = Some(HttpResponse(400, "application/json", "{}")))
    )
    val lastFailure = TaskFailure(
      appId = appD.id,
      taskId = mesos.TaskID.newBuilder().setValue("marathon-task-2").build(),
      state = mesos.TaskState.TASK_FAILED,
      message = "exit 1",
      host = "rack-1",
      version = f.clock.now(),
      timestamp = f.clock.now(),
      slaveId = Some(mesos.SlaveID.newBuilder().setValue("agent-1").build())
    )
    val taskLifeTime = Some(TaskLifeTime(42.0, 40.0))
    val taskStatsByVersion = TaskStatsByVersion(
      maybeStartedAfterLastScaling = Some(TaskStats(taskCounts, taskLifeTime)),
      maybeWithLatestConfig = Some(TaskStats(taskCounts, taskLifeTime)),
      maybeWithOutdatedConfig = Some(TaskStats(taskCounts, taskLifeTime)),
      maybeTotalSummary = Some(TaskStats(taskCounts, taskLifeTime))
    )
    val health = Health(
      instanceId = Instance.Id("a.instance-1c023367-a90b-11e7-9e80-9238e089ec23"),
      firstSuccess = Some(f.clock.now()),
      lastSuccess = Some(f.clock.now()),
      lastFailure = Some(f.clock.now()),
      lastFailureCause = Some("unknown"),
      consecutiveFailures = 0
    )
    val attribute = mesos.Attribute.newBuilder()
      .setName("foo")
      .setType(mesos.Value.Type.TEXT)
      .setText(mesos.Value.Text.newBuilder().setValue("bar"))
      .build()
    val agentInfo = AgentInfo(
      host = "rack-1",
      agentId = Some("agent-1"),
      attributes = Seq(attribute)
    )
    val mesosTaskStatus = mesos.TaskStatus.newBuilder()
      .setTaskId(mesos.TaskID.newBuilder().setValue("marathon-task-2").build())
      .setState(mesos.TaskState.TASK_RUNNING)
      .build()
    val mesosIpAddress = mesos.NetworkInfo.IPAddress.newBuilder().setIpAddress("127.0.0.1").build()
    val taskStatus = Task.Status(
      stagedAt = f.clock.now() - 5.minutes,
      startedAt = Some(f.clock.now()),
      mesosStatus = Some(mesosTaskStatus),
      condition = Condition.Running,
      networkInfo = NetworkInfo("other-host", Seq(8080), Seq(mesosIpAddress))
    )
    val ephermeralTask = LaunchedEphemeral(
      taskId = Task.Id("marathon-task-1"),
      runSpecVersion = f.clock.now(),
      status = taskStatus
    )
    val tasks = Seq(
      EnrichedTask(
        appId = appD.id,
        task = ephermeralTask,
        agentInfo = agentInfo,
        healthCheckResults = Seq(health),
        servicePorts = Seq(80, 433)
      )
    //      EnrichedTask(
    //        appD.id,
    //        task: Task,
    //        agentInfo = agentInfo,
    //        healthCheckResults = Nil,
    //        servicePorts = Nil)
    //      )
    )
    val appInfo = AppInfo(
      app = appD,
      maybeTasks = Some(tasks),
      maybeCounts = Some(taskCounts),
      maybeDeployments = Some(deployments),
      maybeReadinessCheckResults = Some(readinessCheckResults),
      maybeLastTaskFailure = Some(lastFailure),
      maybeTaskStats = Some(taskStatsByVersion)
    )
    f.appInfoService.selectApp(any, any, any) returns Future.successful(Some(appInfo))

    When("we try to fetch an app")
    Get(Uri./.withPath(Uri.Path("/a")), HttpEntity.Empty) ~> f.appsController.route ~> check {
      Then("we receive a response with the app info")
      status should be(StatusCodes.OK)
      val expected =
        """
          |{
          |  "app" : {
          |    "id" : "/a",
          |    "backoffFactor" : 1.15,
          |    "backoffSeconds" : 1,
          |    "cpus" : 1,
          |    "disk" : 0,
          |    "executor" : "",
          |    "instances" : 1,
          |    "labels" : { },
          |    "maxLaunchDelaySeconds" : 3600,
          |    "mem" : 128,
          |    "gpus" : 0,
          |    "networks" : [ {
          |      "mode" : "host"
          |    } ],
          |    "portDefinitions" : [ ],
          |    "requirePorts" : false,
          |    "upgradeStrategy" : {
          |      "maximumOverCapacity" : 1,
          |      "minimumHealthCapacity" : 1
          |    },
          |    "version" : "2015-04-09T12:30:00Z",
          |    "killSelection" : "YOUNGEST_FIRST",
          |    "unreachableStrategy" : {
          |      "inactiveAfterSeconds" : 0,
          |      "expungeAfterSeconds" : 0
          |    },
          |    "tasksStaged" : 0,
          |    "tasksRunning" : 3,
          |    "tasksHealthy" : 2,
          |    "tasksUnhealthy" : 1,
          |    "deployments" : [ { "id" : "foo" }, { "id" : "bar" } ],
          |    "readinessCheckResults" : [ {
          |      "name" : "alice",
          |      "taskId" : "marathon-task-1",
          |      "ready" : true
          |    }, {
          |      "name" : "bob",
          |      "taskId" : "marathon-task-2",
          |      "ready" : false,
          |      "lastResponse" : {
          |        "status" : 400,
          |        "contentType" : "application/json",
          |        "body" : "{}"
          |      }
          |    } ],
          |    "tasks" : [ {
          |      "ipAddresses" : [ {
          |        "ipAddress" : "127.0.0.1",
          |        "protocol" : "IPv4"
          |      } ],
          |      "stagedAt" : "2015-04-09T12:25:00.000Z",
          |      "state" : "TASK_RUNNING",
          |      "ports" : [ 8080 ],
          |      "startedAt" : "2015-04-09T12:30:00.000Z",
          |      "version" : "2015-04-09T12:30:00.000Z",
          |      "id" : "marathon-task-1",
          |      "appId" : "/a",
          |      "slaveId" : "agent-1",
          |      "host" : "rack-1",
          |      "servicePorts" : [ 80, 433 ],
          |      "healthCheckResults" : [ {
          |        "alive" : false,
          |        "consecutiveFailures" : 0,
          |        "firstSuccess" : "2015-04-09T12:30:00.000Z",
          |        "lastFailure" : "2015-04-09T12:30:00.000Z",
          |        "lastSuccess" : "2015-04-09T12:30:00.000Z",
          |        "lastFailureCause" : "unknown",
          |        "instanceId" : "a.instance-1c023367-a90b-11e7-9e80-9238e089ec23"
          |      } ]
          |    } ],
          |    "lastTaskFailure" : {
          |      "appId" : "/a",
          |      "host" : "rack-1",
          |      "message" : "exit 1",
          |      "state" : "TASK_FAILED",
          |      "taskId" : "marathon-task-2",
          |      "timestamp" : "2015-04-09T12:30:00.000Z",
          |      "version" : "2015-04-09T12:30:00.000Z",
          |      "slaveId" : "agent-1"
          |    },
          |    "taskStats" : {
          |      "startedAfterLastScaling" : {
          |        "stats" : {
          |          "counts" : { "staged" : 0, "running" : 3, "healthy" : 2, "unhealthy" : 1 },
          |          "lifeTime" : { "averageSeconds" : 42, "medianSeconds" : 40 }
          |        }
          |      },
          |      "withLatestConfig" : {
          |        "stats" : {
          |          "counts" : { "staged" : 0, "running" : 3, "healthy" : 2, "unhealthy" : 1 },
          |          "lifeTime" : { "averageSeconds" : 42, "medianSeconds" : 40 }
          |        }
          |      },
          |      "withOutdatedConfig" : {
          |        "stats" : {
          |          "counts" : { "staged" : 0, "running" : 3, "healthy" : 2, "unhealthy" : 1 },
          |          "lifeTime" : { "averageSeconds" : 42, "medianSeconds" : 40 }
          |        }
          |      },
          |      "totalSummary" : {
          |        "stats" : {
          |          "counts" : { "staged" : 0, "running" : 3, "healthy" : 2, "unhealthy" : 1 },
          |          "lifeTime" : { "averageSeconds" : 42, "medianSeconds" : 40 }
          |        }
          |      }
          |    }
          |  }
          |}
        """.stripMargin
      JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
    }
  }
}
