package mesosphere.marathon
package core.appinfo.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.appinfo.{AppInfo, EnrichedTask, TaskStatsByVersion}
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.deployment.{DeploymentPlan, DeploymentStep, DeploymentStepInfo}
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.{Health, HealthCheckManager}
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.{Goal, Instance, TestInstanceBuilder}
import mesosphere.marathon.core.pod.{HostNetwork, MesosContainer, PodDefinition}
import mesosphere.marathon.core.readiness.ReadinessCheckResult
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.MesosTaskStatusTestHelper
import mesosphere.marathon.core.task.state.NetworkInfoPlaceholder
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.raml.{Raml, Resources, TaskConversion}
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.TaskFailureRepository
import mesosphere.marathon.test.{GroupCreation, SettableClock}
import play.api.libs.json.Json

import scala.collection.immutable.{Map, Seq}
import scala.concurrent.Future
import scala.concurrent.duration._

class AppInfoBaseDataTest extends UnitTest with GroupCreation {
  import mesosphere.marathon.raml.TaskConversion._

  class Fixture {
    val runSpecId = AbsolutePathId("/test")
    lazy val clock = new SettableClock()
    lazy val instanceTracker = mock[InstanceTracker]
    lazy val healthCheckManager = mock[HealthCheckManager]
    lazy val marathonSchedulerService = mock[MarathonSchedulerService]
    lazy val taskFailureRepository = mock[TaskFailureRepository]
    lazy val groupManager = mock[GroupManager]

    import scala.concurrent.ExecutionContext.Implicits.global

    lazy val baseData = new AppInfoBaseData(
      clock,
      instanceTracker,
      healthCheckManager,
      marathonSchedulerService,
      taskFailureRepository,
      groupManager
    )

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(instanceTracker)
      noMoreInteractions(healthCheckManager)
      noMoreInteractions(marathonSchedulerService)
      noMoreInteractions(taskFailureRepository)
    }

    def fakeInstance(pod: PodDefinition): Instance = {
      val instanceId = Instance.Id.forRunSpec(pod.id)
      val tasks: Map[Task.Id, Task] = pod.containers.iterator.map { ct =>
        val taskId = Task.Id(instanceId, Some(ct))
        taskId -> Task(
          taskId = taskId,
          runSpecVersion = pod.version,
          status = Task.Status.apply(
            stagedAt = clock.now(),
            startedAt = Some(clock.now()),
            mesosStatus = None,
            condition = Condition.Running,
            networkInfo = NetworkInfoPlaceholder()))
      }.toMap

      Instance(
        instanceId = instanceId,
        agentInfo = Some(Instance.AgentInfo("", None, None, None, Nil)),
        state = InstanceState.transitionTo(None, tasks, clock.now(), UnreachableStrategy.default(), Goal.Running),
        tasksMap = tasks,
        runSpec = pod,
        None, "*"
      )
    }
  }

  val app = AppDefinition(AbsolutePathId("/test"), role = "*")
  val other = AppDefinition(AbsolutePathId("/other"), role = "*")
  val pod = PodDefinition(id = AbsolutePathId("/pod"), role = "*", networks = Seq(HostNetwork), containers = Seq(
    MesosContainer(name = "ct1", resources = Resources(0.01, 32))
  ))

  "AppInfoBaseData" should {
    "not embedding anything results in no calls" in {
      val f = new Fixture

      When("getting AppInfos without embeds")
      val appInfo = f.baseData.appInfoFuture(app, Set.empty).futureValue

      Then("we get an empty appInfo")
      appInfo should be(raml.AppInfo.fromParent(parent = Raml.toRaml(app)))

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    "requesting an app with 0 instances" in {
      val f = new Fixture
      Given("one app with 0 instances")
      import scala.concurrent.ExecutionContext.Implicits.global
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.forInstances(Seq.empty[Instance]))
      f.healthCheckManager.statuses(app.id) returns Future.successful(Map.empty[Instance.Id, Seq[Health]])

      When("requesting AppInfos with tasks")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Tasks)).futureValue

      Then("should have 0 taskInfos")
      appInfo.tasks.value should have size 0
    }

    "requesting tasks without health information" in {
      val f = new Fixture
      Given("2 instances: each one with one task")
      val instance1 = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()
      val task1: Task = instance1.appTask
      val task2: Task = instance2.appTask

      import scala.concurrent.ExecutionContext.Implicits.global
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.forInstances(Seq(instance1, instance2)))
      f.healthCheckManager.statuses(app.id) returns Future.successful(Map.empty[Instance.Id, Seq[Health]])

      When("requesting AppInfos with tasks")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Tasks)).futureValue

      val agentInfo = Instance.AgentInfo("host.some", Some("agent-1"), None, None, List())

      val eTask1 = EnrichedTask(app.id, task1, agentInfo, healthCheckResults = Vector(), servicePorts = List(), reservation = None, "*")
      val eTask2 = EnrichedTask(app.id, task2, agentInfo, healthCheckResults = Vector(), servicePorts = List(), reservation = None, "*")

      val eTasks: Seq[raml.Task] = Vector(Raml.toRaml(eTask1), Raml.toRaml(eTask2))

      Then("we get a tasks object in the appInfo")
      appInfo.tasks.value should have size 2
      appInfo.tasks.value should equal (eTasks)
    }

    "requesting tasks retrieves tasks from taskTracker and health infos" in {
      val f = new Fixture
      Given("three tasks in the task tracker")
      val builder1 = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning()
      val builder2 = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning()
      val builder3 = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning()
      val running1: Instance = builder1.getInstance()
      val running2: Instance = builder2.getInstance()
      val running3: Instance = builder3.getInstance()

      import scala.concurrent.ExecutionContext.Implicits.global
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.forInstances(Seq(builder1.getInstance(), builder2.getInstance(), builder3.getInstance())))

      val alive = Health(running2.instanceId, lastSuccess = Some(Timestamp(1)))
      val unhealthy = Health(running3.instanceId, lastFailure = Some(Timestamp(1)))

      f.healthCheckManager.statuses(app.id) returns Future.successful(
        Map(
          running1.instanceId -> Seq.empty,
          running2.instanceId -> Seq(alive),
          running3.instanceId -> Seq(unhealthy)
        )
      )

      When("requesting AppInfos with tasks")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Tasks)).futureValue

      Then("we get a tasks object in the appInfo")
      appInfo.tasks.value should have size 3
      appInfo.tasks.value.map(_.id).toSet should be (Set(
        running1.appTask.taskId.idString,
        running2.appTask.taskId.idString,
        running3.appTask.taskId.idString))

      appInfo should be(raml.AppInfo.fromParent(parent = Raml.toRaml(app), tasks =
        Some(
          Seq(
            Raml.toRaml(EnrichedTask(running1.runSpecId, running1.appTask, TestInstanceBuilder.defaultAgentInfo, Nil, Nil, None, "*")),
            Raml.toRaml(EnrichedTask(running2.runSpecId, running2.appTask, TestInstanceBuilder.defaultAgentInfo, Seq(alive), Nil, None, "*")),
            Raml.toRaml(EnrichedTask(running3.runSpecId, running3.appTask, TestInstanceBuilder.defaultAgentInfo, Seq(unhealthy), Nil, None, "*"))
          )
        )
      ))

      And("the taskTracker should have been called")
      verify(f.instanceTracker, times(1)).instancesBySpec()

      And("the healthCheckManager as well")
      verify(f.healthCheckManager, times(1)).statuses(app.id)

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    "requesting tasks returns an empty array instead of null" in {
      val f = new Fixture
      Given("no tasks in the task tracker")

      import scala.concurrent.ExecutionContext.Implicits.global
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.forInstances(Nil))

      f.healthCheckManager.statuses(app.id) returns Future.successful(Map())

      When("requesting AppInfos with tasks")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Tasks)).futureValue

      Then("we get a tasks object in the appInfo")
      appInfo.tasks.value should have size 0
    }

    "requesting task counts only retrieves tasks from taskTracker and health stats" in {
      val f = new Fixture
      Given("one staged and two running tasks in the taskTracker")
      val stagedBuilder = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskStaged()
      val staged: Instance = stagedBuilder.getInstance()

      val runningBuilder = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning()
      val running: Instance = runningBuilder.getInstance()

      val running2Builder = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning()
      val running2: Instance = running2Builder.getInstance()

      import scala.concurrent.ExecutionContext.Implicits.global
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.forInstances(Seq(stagedBuilder.getInstance(), runningBuilder.getInstance(), running2Builder.getInstance())))

      f.healthCheckManager.statuses(app.id) returns Future.successful(
        Map(
          staged.instanceId -> Seq(),
          running.instanceId -> Seq(Health(running.instanceId, lastFailure = Some(Timestamp(1)))),
          running2.instanceId -> Seq(Health(running2.instanceId, lastSuccess = Some(Timestamp(2))))
        )
      )

      When("requesting AppInfos with counts")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Counts)).futureValue

      Then("we get counts object in the appInfo")
      appInfo should be(raml.AppInfo.fromParent(parent = Raml.toRaml(app), tasksStaged = Some(1), tasksRunning = Some(2), tasksHealthy = Some(1), tasksUnhealthy = Some(1)))

      And("the taskTracker should have been called")
      verify(f.instanceTracker, times(1)).instancesBySpec()

      And("the healthCheckManager as well")
      verify(f.healthCheckManager, times(1)).statuses(app.id)

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    "requesting deployments does not request anything else" in {
      val f = new Fixture
      Given("One related and one unrelated deployment")
      val emptyRootGroup = createRootGroup()
      val relatedDeployment = DeploymentPlan(emptyRootGroup, emptyRootGroup.updateApps(PathId.root, _ => Map(app.id -> app), emptyRootGroup.version))
      val unrelatedDeployment = DeploymentPlan(emptyRootGroup, emptyRootGroup.updateApps(PathId.root, _ => Map(other.id -> other), emptyRootGroup.version))
      f.marathonSchedulerService.listRunningDeployments() returns Future.successful(Seq[DeploymentStepInfo](
        DeploymentStepInfo(relatedDeployment, DeploymentStep(Seq.empty), 1),
        DeploymentStepInfo(unrelatedDeployment, DeploymentStep(Seq.empty), 1)
      ))

      When("Getting AppInfos without counts")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Deployments)).futureValue

      Then("we get an counts in the appInfo")
      appInfo should be(raml.AppInfo.fromParent(parent = Raml.toRaml(app), deployments = Some(Seq(raml.Identifiable(relatedDeployment.id)))))

      And("the marathonSchedulerService should have been called to retrieve the deployments")
      verify(f.marathonSchedulerService, times(1)).listRunningDeployments()

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    "requesting deployments does work if no deployments are running" in {
      val f = new Fixture
      Given("No deployments")
      f.marathonSchedulerService.listRunningDeployments() returns Future.successful(
        Seq.empty[DeploymentStepInfo]
      )

      When("Getting AppInfos with deployments")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Deployments)).futureValue

      Then("we get an empty list of deployments")
      appInfo should be(raml.AppInfo.fromParent(parent = Raml.toRaml(app), deployments = Some(Seq.empty)))

      And("the marathonSchedulerService should have been called to retrieve the deployments")
      verify(f.marathonSchedulerService, times(1)).listRunningDeployments()

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    "requesting readiness check results" in {
      val f = new Fixture
      Given("One related and one unrelated deployment")
      val emptyRootGroup = createRootGroup()
      val deployment = DeploymentPlan(emptyRootGroup, emptyRootGroup.updateApps(PathId.root, _ => Map(app.id -> app), emptyRootGroup.version))
      val instanceId = Instance.Id.forRunSpec(app.id)
      val taskId: Task.Id = Task.Id(instanceId)
      val result = ReadinessCheckResult("foo", taskId, ready = false, None)
      val resultRaml = raml.TaskReadinessCheckResult("foo", taskId.idString, ready = false, None)
      f.marathonSchedulerService.listRunningDeployments() returns Future.successful(Seq[DeploymentStepInfo](
        DeploymentStepInfo(deployment, DeploymentStep(Seq.empty), 1, Map(taskId -> result))
      ))

      When("Getting AppInfos without counts")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Readiness)).futureValue

      Then("we get an counts in the appInfo")
      appInfo should be(raml.AppInfo.fromParent(parent = Raml.toRaml(app), readinessCheckResults = Some(Seq(resultRaml))))

      And("the marathonSchedulerService should have been called to retrieve the deployments")
      verify(f.marathonSchedulerService, times(1)).listRunningDeployments()

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    "requesting lastTaskFailure when one exists" in {
      val f = new Fixture
      Given("One last taskFailure")
      f.taskFailureRepository.get(app.id) returns Future.successful(Some(TaskFailureTestHelper.taskFailure))

      When("Getting AppInfos with last task failures")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.LastTaskFailure)).futureValue

      Then("we get the failure in the app info")
      appInfo should be(raml.AppInfo.fromParent(parent = Raml.toRaml(app), lastTaskFailure = Some(Raml.toRaml(TaskFailureTestHelper.taskFailure)(TaskConversion.taskFailureRamlWrite))))

      And("the taskFailureRepository should have been called to retrieve the failure")
      verify(f.taskFailureRepository, times(1)).get(app.id)

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    "requesting lastTaskFailure when None exist" in {
      val f = new Fixture
      Given("no taskFailure")
      f.taskFailureRepository.get(app.id) returns Future.successful(None)

      When("Getting AppInfos with last task failures")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.LastTaskFailure)).futureValue

      Then("we get no failure in the app info")
      appInfo should be(raml.AppInfo.fromParent(parent = Raml.toRaml(app)))

      And("the taskFailureRepository should have been called to retrieve the failure")
      verify(f.taskFailureRepository, times(1)).get(app.id)

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    "requesting taskStats" in {
      val f = new Fixture
      Given("one staged and two running tasks in the taskTracker")
      val stagedBuilder = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskStaged(stagedAt = Timestamp((f.clock.now() - 10.seconds).millis))
      val staged: Instance = stagedBuilder.getInstance()
      val runningBuilder = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning(stagedAt = Timestamp((f.clock.now() - 11.seconds).millis))
      val running: Instance = runningBuilder.getInstance()
      val running2Builder = TestInstanceBuilder.newBuilder(f.runSpecId).addTaskRunning(stagedAt = Timestamp((f.clock.now() - 11.seconds).millis))
      val running2: Instance = running2Builder.getInstance()

      import scala.concurrent.ExecutionContext.Implicits.global
      val instances = Seq(stagedBuilder.getInstance(), runningBuilder.getInstance(), running2Builder.getInstance())
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.forInstances(instances))

      val statuses: Map[Instance.Id, Seq[Health]] = Map(
        staged.instanceId -> Seq(),
        running.instanceId -> Seq(Health(running.instanceId, lastFailure = Some(Timestamp(1)))),
        running2.instanceId -> Seq(Health(running2.instanceId, lastSuccess = Some(Timestamp(2))))
      )
      f.healthCheckManager.statuses(app.id) returns Future.successful(statuses)

      When("requesting AppInfos with taskStats")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.TaskStats)).futureValue

      Then("we get taskStats object in the appInfo")
      // we check the calculation of the stats in TaskStatsByVersionTest, so we only check some basic stats

      withClue(Json.prettyPrint(Json.toJson(appInfo))) {
        appInfo.tasksStats should not be empty
        appInfo.tasksStats.value.totalSummary.value.stats.counts.staged should be (1)
        appInfo.tasksStats.value.totalSummary.value.stats.counts.running should be (2)

        appInfo should be(raml.AppInfo.fromParent(
          parent = Raml.toRaml(app),
          tasksStats = Some(TaskStatsByVersion(f.clock.now(), app.versionInfo, instances, statuses))
        ))
      }

      And("the taskTracker should have been called")
      verify(f.instanceTracker, times(1)).instancesBySpec()

      And("the healthCheckManager as well")
      verify(f.healthCheckManager, times(1)).statuses(app.id)

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    "Combining embed options work" in {
      val f = new Fixture
      Given("One last taskFailure and no deployments")
      f.taskFailureRepository.get(app.id) returns Future.successful(Some(TaskFailureTestHelper.taskFailure))
      f.marathonSchedulerService.listRunningDeployments() returns Future.successful(
        Seq.empty[DeploymentStepInfo]
      )

      When("Getting AppInfos with last task failures and deployments")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Deployments)).futureValue

      Then("we get the failure in the app info")
      appInfo should be(raml.AppInfo.fromParent(
        parent = Raml.toRaml(app),
        lastTaskFailure = Some(Raml.toRaml(TaskFailureTestHelper.taskFailure)(TaskConversion.taskFailureRamlWrite)),
        deployments = Some(Seq.empty)
      ))

      And("the taskFailureRepository should have been called to retrieve the failure")
      verify(f.taskFailureRepository, times(1)).get(app.id)

      And("the marathonSchedulerService should have been called to retrieve the deployments")
      verify(f.marathonSchedulerService, times(1)).listRunningDeployments()

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    "pod statuses xref the correct spec versions" in {
      implicit val f = new Fixture
      val v1 = VersionInfo.OnlyVersion(f.clock.now())
      val podspec1 = pod.copy(versionInfo = v1)

      f.clock.advanceBy(1.minute)

      // the same as podspec1 but with a new version and a renamed container
      val v2 = VersionInfo.OnlyVersion(f.clock.now())
      val podspec2 = pod.copy(versionInfo = v2, containers = pod.containers.map(_.copy(name = "ct2")))

      f.clock.advanceBy(1.minute)

      When("requesting pod instance status")
      val instanceV1 = f.fakeInstance(podspec1)
      val maybeStatus1 = f.baseData.podInstanceStatus(instanceV1)

      Then("instance referring to v1 spec should reference container ct1")
      maybeStatus1 should be('nonEmpty)
      maybeStatus1.foreach { status =>
        status.containers.size should be(1)
        status.containers(0).name should be("ct1")
      }

      And("instance referring to v2 spec should reference container ct2")
      val instanceV2 = f.fakeInstance(podspec2)
      val maybeStatus2 = f.baseData.podInstanceStatus(instanceV2)

      maybeStatus2 should be('nonEmpty)
      maybeStatus2.foreach { status =>
        status.containers.size should be(1)
        status.containers(0).name should be("ct2")
      }

      And("instance referring to an app instead of pod doesn't have any status")
      val v3 = VersionInfo.OnlyVersion(f.clock.now())
      val appInstance = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()
      val maybeStatus3 = f.baseData.podInstanceStatus(appInstance)

      maybeStatus3 should be ('empty)
    }

    "pod status for instances already removed from the group repo doesn't throw an exception" in { //DCOS-16151
      implicit val f = new Fixture

      f.taskFailureRepository.get(pod.id) returns Future.successful(None)

      Given("a pod definition")
      val instance1 = f.fakeInstance(pod)

      import scala.concurrent.ExecutionContext.Implicits.global
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.forInstances(Seq(instance1)))

      And("with no instances in the repo")
      f.groupManager.podVersion(any, any) returns Future.successful(None)
      f.marathonSchedulerService.listRunningDeployments() returns Future.successful(Seq.empty)

      Then("requesting pod status should not throw an exception")
      noException should be thrownBy {
        f.baseData.podStatus(pod).futureValue
      }
    }

    "show a pod status with a task in TASK_UNKNOWN state" in {
      import scala.concurrent.ExecutionContext.Implicits.global
      val f = new Fixture
      Given("A pod instance")
      val taskFailure = TaskFailureTestHelper.taskFailure
      f.taskFailureRepository.get(pod.id) returns Future.successful(Some(taskFailure))
      val instance1 = {
        val instanceId = Instance.Id.forRunSpec(pod.id)
        val tasks: Map[Task.Id, Task] = pod.containers.iterator.map { ct =>
          val taskId = Task.Id(instanceId, Some(ct))
          taskId -> Task(
            taskId = taskId,
            runSpecVersion = pod.version,
            status = Task.Status.apply(
              stagedAt = f.clock.now(),
              startedAt = Some(f.clock.now()),
              mesosStatus = Some(MesosTaskStatusTestHelper.unknown(taskId)),
              condition = Condition.Unknown,
              networkInfo = NetworkInfoPlaceholder()))
        }.toMap

        Instance(
          instanceId = instanceId,
          agentInfo = Some(Instance.AgentInfo("", None, None, None, Nil)),
          state = InstanceState.transitionTo(None, tasks, f.clock.now(), UnreachableStrategy.default(), Goal.Running),
          tasksMap = tasks,
          runSpec = pod,
          None, "*"
        )
      }
      f.instanceTracker.instancesBySpec() returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(Seq(instance1)))

      And("an instance in the repo")
      f.groupManager.podVersion(any, any) returns Future.successful(Some(pod))
      f.marathonSchedulerService.listRunningDeployments() returns Future.successful(Seq.empty)

      When("Getting pod status with last task failures")
      val podStatus = f.baseData.podStatus(pod).futureValue

      Then("we get the failure in the app info")
      podStatus.instances should have size (pod.instances)
    }

    "requesting Pod lastTaskFailure when one exists" in {
      import scala.concurrent.ExecutionContext.Implicits.global
      val f = new Fixture
      Given("A pod definition")
      val taskFailure = TaskFailureTestHelper.taskFailure
      f.taskFailureRepository.get(pod.id) returns Future.successful(Some(taskFailure))
      val instance1 = {
        val instance = f.fakeInstance(pod)
        val task = instance.tasksMap.head._2
        val failedTask = task.copy(taskId = Task.Id.parse(taskFailure.taskId))
        instance.copy(tasksMap = instance.tasksMap + (failedTask.taskId -> failedTask))
      }
      f.instanceTracker.instancesBySpec() returns Future.successful(InstanceTracker.InstancesBySpec.forInstances(Seq(instance1)))

      And("an instance in the repo")
      f.groupManager.podVersion(any, any) returns Future.successful(Some(pod))
      f.marathonSchedulerService.listRunningDeployments() returns Future.successful(Seq.empty)

      When("Getting pod status with last task failures")
      val podStatus = f.baseData.podStatus(pod).futureValue

      Then("we get the failure in the app info")
      podStatus.terminationHistory.head.instanceID shouldEqual instance1.instanceId.idString
    }
  }
}
