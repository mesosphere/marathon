package mesosphere.marathon
package core.appinfo.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.appinfo.{ AppInfo, EnrichedTask, TaskCounts, TaskStatsByVersion }
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.deployment.{ DeploymentPlan, DeploymentStep, DeploymentStepInfo }
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.health.{ Health, HealthCheckManager }
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.pod.{ HostNetwork, MesosContainer, PodDefinition }
import mesosphere.marathon.core.readiness.ReadinessCheckResult
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfoPlaceholder
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.TaskFailureRepository
import mesosphere.marathon.test.GroupCreation
import play.api.libs.json.Json

import scala.collection.immutable.{ Map, Seq }
import scala.concurrent.Future
import scala.concurrent.duration._

class AppInfoBaseDataTest extends UnitTest with GroupCreation {

  class Fixture {
    val runSpecId = PathId("/test")
    lazy val clock = ConstantClock()
    lazy val instanceTracker = mock[InstanceTracker]
    lazy val healthCheckManager = mock[HealthCheckManager]
    lazy val marathonSchedulerService = mock[MarathonSchedulerService]
    lazy val taskFailureRepository = mock[TaskFailureRepository]
    lazy val groupManager = mock[GroupManager]

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
  }

  val app = AppDefinition(PathId("/test"))
  val other = AppDefinition(PathId("/other"))
  val pod = PodDefinition(id = PathId("/pod"), networks = Seq(HostNetwork), containers = Seq(
    MesosContainer(name = "ct1", resources = Resources(0.01, 32))
  ))

  "AppInfoBaseData" should {
    "not embedding anything results in no calls" in {
      val f = new Fixture

      When("getting AppInfos without embeds")
      val appInfo = f.baseData.appInfoFuture(app, Set.empty).futureValue

      Then("we get an empty appInfo")
      appInfo should be(AppInfo(app))

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    "requesting an app with 0 instances" in {
      val f = new Fixture
      Given("one app with 0 instances")
      import mesosphere.marathon.core.async.ExecutionContexts.global
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(app.id, Seq.empty[Instance])))
      f.healthCheckManager.statuses(app.id) returns Future.successful(Map.empty[Instance.Id, Seq[Health]])

      When("requesting AppInfos with tasks")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Tasks)).futureValue

      Then("should have 0 taskInfos")
      appInfo.maybeTasks.get should have size 0
    }

    "requesting tasks without health information" in {
      val f = new Fixture
      Given("2 instances: each one with one task")
      val instance1 = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()
      val instance2 = TestInstanceBuilder.newBuilder(app.id).addTaskRunning().getInstance()
      val task1: Task = instance1.appTask
      val task2: Task = instance2.appTask

      import mesosphere.marathon.core.async.ExecutionContexts.global
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(app.id, Seq(instance1, instance2))))
      f.healthCheckManager.statuses(app.id) returns Future.successful(Map.empty[Instance.Id, Seq[Health]])

      When("requesting AppInfos with tasks")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Tasks)).futureValue

      Then("we get a tasks object in the appInfo")
      appInfo.maybeTasks.get should have size 2
      appInfo.maybeTasks.value.map(_.task) should equal (Seq(task1, task2))
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

      import mesosphere.marathon.core.async.ExecutionContexts.global
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(app.id, Seq(builder1.getInstance(), builder2.getInstance(), builder3.getInstance()))))

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
      appInfo.maybeTasks should not be empty
      appInfo.maybeTasks.get.map(_.appId.toString) should have size 3
      appInfo.maybeTasks.get.map(_.task.taskId.idString).toSet should be (Set(
        running1.appTask.taskId.idString,
        running2.appTask.taskId.idString,
        running3.appTask.taskId.idString))

      appInfo should be(AppInfo(app, maybeTasks = Some(
        Seq(
          EnrichedTask(app.id, running1.appTask, running1.agentInfo, Seq.empty),
          EnrichedTask(app.id, running2.appTask, running2.agentInfo, Seq(alive)),
          EnrichedTask(app.id, running3.appTask, running3.agentInfo, Seq(unhealthy))
        )
      )))

      And("the taskTracker should have been called")
      verify(f.instanceTracker, times(1)).instancesBySpec()

      And("the healthCheckManager as well")
      verify(f.healthCheckManager, times(1)).statuses(app.id)

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
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

      import mesosphere.marathon.core.async.ExecutionContexts.global
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(app.id, Seq(stagedBuilder.getInstance(), runningBuilder.getInstance(), running2Builder.getInstance()))))

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
      appInfo should be(AppInfo(app, maybeCounts = Some(
        TaskCounts(tasksStaged = 1, tasksRunning = 2, tasksHealthy = 1, tasksUnhealthy = 1)
      )))

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
      val relatedDeployment = DeploymentPlan(emptyRootGroup, emptyRootGroup.updateApps(PathId.empty, _ => Map(app.id -> app), emptyRootGroup.version))
      val unrelatedDeployment = DeploymentPlan(emptyRootGroup, emptyRootGroup.updateApps(PathId.empty, _ => Map(other.id -> other), emptyRootGroup.version))
      f.marathonSchedulerService.listRunningDeployments() returns Future.successful(Seq[DeploymentStepInfo](
        DeploymentStepInfo(relatedDeployment, DeploymentStep(Seq.empty), 1),
        DeploymentStepInfo(unrelatedDeployment, DeploymentStep(Seq.empty), 1)
      ))

      When("Getting AppInfos without counts")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Deployments)).futureValue

      Then("we get an counts in the appInfo")
      appInfo should be(AppInfo(app, maybeDeployments = Some(
        Seq(Identifiable(relatedDeployment.id))
      )))

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
      appInfo should be(AppInfo(app, maybeDeployments = Some(
        Seq.empty
      )))

      And("the marathonSchedulerService should have been called to retrieve the deployments")
      verify(f.marathonSchedulerService, times(1)).listRunningDeployments()

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    "requesting readiness check results" in {
      val f = new Fixture
      Given("One related and one unrelated deployment")
      val emptyRootGroup = createRootGroup()
      val deployment = DeploymentPlan(emptyRootGroup, emptyRootGroup.updateApps(PathId.empty, _ => Map(app.id -> app), emptyRootGroup.version))
      val taskId: Task.Id = Task.Id.forRunSpec(app.id)
      val result = ReadinessCheckResult("foo", taskId, ready = false, None)
      f.marathonSchedulerService.listRunningDeployments() returns Future.successful(Seq[DeploymentStepInfo](
        DeploymentStepInfo(deployment, DeploymentStep(Seq.empty), 1, Map(taskId -> result))
      ))

      When("Getting AppInfos without counts")
      val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Readiness)).futureValue

      Then("we get an counts in the appInfo")
      appInfo should be(AppInfo(app, maybeReadinessCheckResults = Some(
        Seq(result)
      )))

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
      appInfo should be(AppInfo(app, maybeLastTaskFailure = Some(
        TaskFailureTestHelper.taskFailure
      )))

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
      appInfo should be(AppInfo(app))

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

      import mesosphere.marathon.core.async.ExecutionContexts.global
      val instances = Seq(stagedBuilder.getInstance(), runningBuilder.getInstance(), running2Builder.getInstance())
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(app.id, instances)))

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

      import mesosphere.marathon.api.v2.json.Formats._
      withClue(Json.prettyPrint(Json.toJson(appInfo))) {
        appInfo.maybeTaskStats should not be empty
        appInfo.maybeTaskStats.get.maybeTotalSummary should not be empty
        appInfo.maybeTaskStats.get.maybeTotalSummary.get.counts.tasksStaged should be (1)
        appInfo.maybeTaskStats.get.maybeTotalSummary.get.counts.tasksRunning should be (2)

        appInfo should be(AppInfo(
          app,
          maybeTaskStats = Some(TaskStatsByVersion(f.clock.now(), app.versionInfo, instances, statuses))
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
      appInfo should be(AppInfo(
        app,
        maybeLastTaskFailure = Some(TaskFailureTestHelper.taskFailure),
        maybeDeployments = Some(Seq.empty)
      ))

      And("the taskFailureRepository should have been called to retrieve the failure")
      verify(f.taskFailureRepository, times(1)).get(app.id)

      And("the marathonSchedulerService should have been called to retrieve the deployments")
      verify(f.marathonSchedulerService, times(1)).listRunningDeployments()

      And("we have no more interactions")
      f.verifyNoMoreInteractions()
    }

    def fakeInstance(pod: PodDefinition)(implicit f: Fixture): Instance = {
      val instanceId = Instance.Id.forRunSpec(pod.id)
      val tasks: Map[Task.Id, Task] = pod.containers.map { ct =>
        val taskId = Task.Id.forInstanceId(instanceId, Some(ct))
        taskId -> Task.LaunchedEphemeral(
          taskId = taskId,
          runSpecVersion = pod.version,
          status = Task.Status.apply(
            stagedAt = f.clock.now(),
            startedAt = Some(f.clock.now()),
            mesosStatus = None,
            condition = Condition.Running,
            networkInfo = NetworkInfoPlaceholder()))
      }(collection.breakOut)

      Instance(
        instanceId = instanceId,
        agentInfo = Instance.AgentInfo("", None, Nil),
        state = InstanceState(None, tasks, f.clock.now(), UnreachableStrategy.default()),
        tasksMap = tasks,
        runSpecVersion = pod.version,
        unreachableStrategy = UnreachableStrategy.default()
      )
    }

    "pod statuses xref the correct spec versions" in {
      implicit val f = new Fixture
      val v1 = f.clock.now()
      val podspec1 = pod.copy(version = v1)

      f.clock += 1.minute

      // the same as podspec1 but with a new version and a renamed container
      val v2 = f.clock.now()
      val podspec2 = pod.copy(version = v2, containers = pod.containers.map(_.copy(name = "ct2")))

      Given("multiple versions of the same pod specification")
      def findPodSpecByVersion(version: Timestamp): Option[PodDefinition] = {
        if (v1 == version) Some(podspec1)
        else if (v2 == version) Some(podspec2)
        else Option.empty[PodDefinition]
      }

      f.clock += 1.minute

      When("requesting pod instance status")
      val instanceV1 = fakeInstance(podspec1)
      val maybeStatus1 = f.baseData.podInstanceStatus(instanceV1)(findPodSpecByVersion)

      Then("instance referring to v1 spec should reference container ct1")
      maybeStatus1 should be('nonEmpty)
      maybeStatus1.foreach { status =>
        status.containers.size should be(1)
        status.containers(0).name should be("ct1")
      }

      And("instance referring to v2 spec should reference container ct2")
      val instanceV2 = fakeInstance(podspec2)
      val maybeStatus2 = f.baseData.podInstanceStatus(instanceV2)(findPodSpecByVersion)

      maybeStatus2 should be('nonEmpty)
      maybeStatus2.foreach { status =>
        status.containers.size should be(1)
        status.containers(0).name should be("ct2")
      }

      And("instance referring to a bogus version doesn't have any status")
      val v3 = f.clock.now()
      val instanceV3 = fakeInstance(pod.copy(version = v3))
      val maybeStatus3 = f.baseData.podInstanceStatus(instanceV3)(findPodSpecByVersion)

      maybeStatus3 should be ('empty)
    }

    "pod status for instances already removed from the group repo doesn't throw an exception" in { //DCOS-16151
      implicit val f = new Fixture

      Given("a pod definition")
      val instance1 = fakeInstance(pod)

      import mesosphere.marathon.core.async.ExecutionContexts.global
      f.instanceTracker.instancesBySpec() returns
        Future.successful(InstanceTracker.InstancesBySpec.of(InstanceTracker.SpecInstances.forInstances(pod.id, Seq(instance1))))

      And("with no instances in the repo")
      f.groupManager.podVersion(any, any) returns Future.successful(None)
      f.marathonSchedulerService.listRunningDeployments() returns Future.successful(Seq.empty)

      When("requesting pod status")
      val status = f.baseData.podStatus(pod).futureValue

      Then("no exception was thrown so status was successfully fetched")
    }
  }
}
