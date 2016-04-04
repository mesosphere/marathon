package mesosphere.marathon.core.appinfo.impl

import mesosphere.marathon.core.appinfo.{ AppInfo, EnrichedTask, TaskCounts, TaskStatsByVersion }
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.readiness.ReadinessCheckResult
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.health.{ Health, HealthCheckManager }
import mesosphere.marathon.state._
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.DeploymentManager.DeploymentStepInfo
import mesosphere.marathon.upgrade.{ DeploymentPlan, DeploymentStep }
import mesosphere.marathon.{ MarathonTestHelper, MarathonSchedulerService, MarathonSpec }
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class AppInfoBaseDataTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {
  import mesosphere.FutureTestSupport._

  class Fixture {
    lazy val clock = ConstantClock()
    lazy val taskTracker = mock[TaskTracker]
    lazy val healthCheckManager = mock[HealthCheckManager]
    lazy val marathonSchedulerService = mock[MarathonSchedulerService]
    lazy val taskFailureRepository = mock[TaskFailureRepository]

    lazy val baseData = new AppInfoBaseData(
      clock,
      taskTracker,
      healthCheckManager,
      marathonSchedulerService,
      taskFailureRepository
    )

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskTracker)
      noMoreInteractions(healthCheckManager)
      noMoreInteractions(marathonSchedulerService)
      noMoreInteractions(taskFailureRepository)
    }
  }

  val app = AppDefinition(PathId("/test"))
  val other = AppDefinition(PathId("/other"))

  test("not embedding anything results in no calls") {
    val f = new Fixture

    When("getting AppInfos without embeds")
    val appInfo = f.baseData.appInfoFuture(app, Set.empty).futureValue

    Then("we get an empty appInfo")
    appInfo should be(AppInfo(app))

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting tasks retrieves tasks from taskTracker and health infos") {
    val f = new Fixture
    Given("three tasks in the task tracker")
    val running1 = MarathonTestHelper.runningTask("task1")
    val running2 = MarathonTestHelper.runningTask("task2")
    val running3 = MarathonTestHelper.runningTask("task3")

    import scala.concurrent.ExecutionContext.Implicits.global
    f.taskTracker.tasksByApp()(global) returns
      Future.successful(TaskTracker.TasksByApp.of(TaskTracker.AppTasks.forTasks(app.id, Iterable(running1, running2, running3))))

    val alive = Health(running2.taskId, lastSuccess = Some(Timestamp(1)))
    val unhealthy = Health(running3.taskId, lastFailure = Some(Timestamp(1)))

    f.healthCheckManager.statuses(app.id) returns Future.successful(
      Map(
        running1.taskId -> Seq.empty,
        running2.taskId -> Seq(alive),
        running3.taskId -> Seq(unhealthy)
      )
    )

    When("requesting AppInfos with tasks")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Tasks)).futureValue

    Then("we get a tasks object in the appInfo")
    appInfo.maybeTasks should not be empty
    appInfo.maybeTasks.get.map(_.appId.toString) should have size 3
    appInfo.maybeTasks.get.map(_.task.taskId.idString).toSet should be (Set("task1", "task2", "task3"))

    appInfo should be(AppInfo(app, maybeTasks = Some(
      Seq(
        EnrichedTask(app.id, running1, Seq.empty),
        EnrichedTask(app.id, running2, Seq(alive)),
        EnrichedTask(app.id, running3, Seq(unhealthy))
      )
    )))

    And("the taskTracker should have been called")
    verify(f.taskTracker, times(1)).tasksByApp()(global)

    And("the healthCheckManager as well")
    verify(f.healthCheckManager, times(1)).statuses(app.id)

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting task counts only retrieves tasks from taskTracker and health stats") {
    val f = new Fixture
    Given("one staged and two running tasks in the taskTracker")
    val staged = MarathonTestHelper.stagedTaskProto("task1")
    val running = MarathonTestHelper.runningTaskProto("task2")
    val running2 = running.toBuilder.setId("task3").buildPartial()

    import scala.concurrent.ExecutionContext.Implicits.global
    f.taskTracker.tasksByApp()(global) returns
      Future.successful(TaskTracker.TasksByApp.of(TaskTracker.AppTasks(app.id, Iterable(staged, running, running2))))

    f.healthCheckManager.statuses(app.id) returns Future.successful(
      Map(
        Task.Id("task1") -> Seq(),
        Task.Id("task2") -> Seq(Health(Task.Id("task2"), lastFailure = Some(Timestamp(1)))),
        Task.Id("task3") -> Seq(Health(Task.Id("task3"), lastSuccess = Some(Timestamp(2))))
      )
    )

    When("requesting AppInfos with counts")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.Counts)).futureValue

    Then("we get counts object in the appInfo")
    appInfo should be(AppInfo(app, maybeCounts = Some(
      TaskCounts(tasksStaged = 1, tasksRunning = 2, tasksHealthy = 1, tasksUnhealthy = 1)
    )))

    And("the taskTracker should have been called")
    verify(f.taskTracker, times(1)).tasksByApp()(global)

    And("the healthCheckManager as well")
    verify(f.healthCheckManager, times(1)).statuses(app.id)

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting deployments does not request anything else") {
    val f = new Fixture
    Given("One related and one unrelated deployment")
    val emptyGroup = Group.empty
    val relatedDeployment = DeploymentPlan(emptyGroup, emptyGroup.copy(apps = Set(app)))
    val unrelatedDeployment = DeploymentPlan(emptyGroup, emptyGroup.copy(apps = Set(other)))
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

  test("requesting deployments does work if no deployments are running") {
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

  test("requesting readiness check results") {
    val f = new Fixture
    Given("One related and one unrelated deployment")
    val emptyGroup = Group.empty
    val deployment = DeploymentPlan(emptyGroup, emptyGroup.copy(apps = Set(app)))
    val taskId: Task.Id = Task.Id.forApp(app.id)
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

  test("requesting lastTaskFailure when one exists") {
    val f = new Fixture
    Given("One last taskFailure")
    f.taskFailureRepository.current(app.id) returns Future.successful(Some(TaskFailureTestHelper.taskFailure))

    When("Getting AppInfos with last task failures")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.LastTaskFailure)).futureValue

    Then("we get the failure in the app info")
    appInfo should be(AppInfo(app, maybeLastTaskFailure = Some(
      TaskFailureTestHelper.taskFailure
    )))

    And("the taskFailureRepository should have been called to retrieve the failure")
    verify(f.taskFailureRepository, times(1)).current(app.id)

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting lastTaskFailure when None exist") {
    val f = new Fixture
    Given("no taskFailure")
    f.taskFailureRepository.current(app.id) returns Future.successful(None)

    When("Getting AppInfos with last task failures")
    val appInfo = f.baseData.appInfoFuture(app, Set(AppInfo.Embed.LastTaskFailure)).futureValue

    Then("we get no failure in the app info")
    appInfo should be(AppInfo(app))

    And("the taskFailureRepository should have been called to retrieve the failure")
    verify(f.taskFailureRepository, times(1)).current(app.id)

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("requesting taskStats") {
    val f = new Fixture
    Given("one staged and two running tasks in the taskTracker")
    val staged = MarathonTestHelper.stagedTask("task1", stagedAt = (f.clock.now() - 10.seconds).toDateTime.getMillis)
    val running = MarathonTestHelper.runningTask("task2", stagedAt = (f.clock.now() - 11.seconds).toDateTime.getMillis)
    val running2 = MarathonTestHelper.runningTask("task3", stagedAt = (f.clock.now() - 11.seconds).toDateTime.getMillis)

    import scala.concurrent.ExecutionContext.Implicits.global
    val tasks: Set[Task] = Set(staged, running, running2)
    f.taskTracker.tasksByApp()(global) returns
      Future.successful(TaskTracker.TasksByApp.of(TaskTracker.AppTasks.forTasks(app.id, tasks)))

    val statuses: Map[Task.Id, Seq[Health]] = Map(
      staged.taskId -> Seq(),
      running.taskId -> Seq(Health(running.taskId, lastFailure = Some(Timestamp(1)))),
      running2.taskId -> Seq(Health(running2.taskId, lastSuccess = Some(Timestamp(2))))
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
        maybeTaskStats = Some(TaskStatsByVersion(f.clock.now(), app.versionInfo, tasks, statuses))
      ))
    }

    And("the taskTracker should have been called")
    verify(f.taskTracker, times(1)).tasksByApp()(global)

    And("the healthCheckManager as well")
    verify(f.healthCheckManager, times(1)).statuses(app.id)

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("Combining embed options work") {
    val f = new Fixture
    Given("One last taskFailure and no deployments")
    f.taskFailureRepository.current(app.id) returns Future.successful(Some(TaskFailureTestHelper.taskFailure))
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
    verify(f.taskFailureRepository, times(1)).current(app.id)

    And("the marathonSchedulerService should have been called to retrieve the deployments")
    verify(f.marathonSchedulerService, times(1)).listRunningDeployments()

    And("we have no more interactions")
    f.verifyNoMoreInteractions()
  }

}
