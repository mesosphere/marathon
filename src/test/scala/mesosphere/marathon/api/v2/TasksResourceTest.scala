package mesosphere.marathon.api.v2

import java.util.Collections

import mesosphere.marathon._
import mesosphere.marathon.api.{ TaskKiller, TestAuthFixture }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, TaskTracker }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.plugin.auth.Identity
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.state.{ AppDefinition, Group, GroupManager, Timestamp }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.{ DeploymentPlan, DeploymentStep }
import org.mockito.Mockito._
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class TasksResourceTest extends MarathonSpec with GivenWhenThen with Matchers with Mockito {

  test("killTasks") {
    Given("two apps and 1 task each")
    val app1 = "/my/app-1".toRootPath
    val app2 = "/my/app-2".toRootPath
    val taskId1 = Task.Id.forApp(app1).idString
    val taskId2 = Task.Id.forApp(app2).idString
    val body = s"""{"ids": ["$taskId1", "$taskId2"]}"""
    val bodyBytes = body.toCharArray.map(_.toByte)

    val task1 = MarathonTestHelper.stagedTask(taskId1)
    val task2 = MarathonTestHelper.runningTask(taskId2)

    config.zkTimeoutDuration returns 5.seconds
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.forTasks(task1, task2)
    taskKiller.kill(any, any, any)(any) returns Future.successful(Iterable.empty[Task])
    groupManager.app(app1) returns Future.successful(Some(AppDefinition(app1)))
    groupManager.app(app2) returns Future.successful(Some(AppDefinition(app2)))

    When("we ask to kill both tasks")
    val response = taskResource.killTasks(scale = false, force = false, wipe = false, body = bodyBytes, auth.request)

    Then("The response should be OK")
    response.getStatus shouldEqual 200

    And("The response is the list of killed tasks")
    response.getEntity shouldEqual """{"tasks":[]}"""

    And("Both tasks should be requested to be killed")
    verify(taskKiller).kill(eq(app1), any, any)(any)
    verify(taskKiller).kill(eq(app2), any, any)(any)

    And("nothing else should be called on the TaskKiller")
    noMoreInteractions(taskKiller)
  }

  test("killTasks with force") {
    Given("two apps and 1 task each")
    val app1 = "/my/app-1".toRootPath
    val app2 = "/my/app-2".toRootPath
    val taskId1 = Task.Id.forApp(app1).idString
    val taskId2 = Task.Id.forApp(app2).idString
    val body = s"""{"ids": ["$taskId1", "$taskId2"]}"""
    val bodyBytes = body.toCharArray.map(_.toByte)
    val deploymentPlan = new DeploymentPlan("plan", Group.empty, Group.empty, Seq.empty[DeploymentStep], Timestamp.zero)

    val task1 = MarathonTestHelper.runningTask(taskId1)
    val task2 = MarathonTestHelper.stagedTask(taskId2)

    config.zkTimeoutDuration returns 5.seconds
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.forTasks(task1, task2)
    taskKiller.killAndScale(any, any)(any) returns Future.successful(deploymentPlan)
    groupManager.app(app1) returns Future.successful(Some(AppDefinition(app1)))
    groupManager.app(app2) returns Future.successful(Some(AppDefinition(app2)))

    When("we ask to kill both tasks")
    val response = taskResource.killTasks(scale = true, force = true, wipe = false, body = bodyBytes, auth.request)

    Then("The response should be OK")
    response.getStatus shouldEqual 200

    And("Should create a deployment")
    response.getEntity shouldEqual """{"version":"1970-01-01T00:00:00.000Z","deploymentId":"plan"}"""

    And("app1 and app2 is killed with force")
    verify(taskKiller).killAndScale(eq(Map(app1 -> Iterable(task1), app2 -> Iterable(task2))), eq(true))(any)

    And("nothing else should be called on the TaskKiller")
    noMoreInteractions(taskKiller)
  }

  test("killTasks with scale and wipe fails") {
    Given("a request")
    val app1 = "/my/app-1".toRootPath
    val taskId1 = Task.Id.forApp(app1).idString
    val body = s"""{"ids": ["$taskId1"]}"""
    val bodyBytes = body.toCharArray.map(_.toByte)

    When("we ask to scale AND wipe")
    val exception = intercept[BadRequestException] {
      taskResource.killTasks(scale = true, force = false, wipe = true, body = bodyBytes, auth.request)
    }

    Then("an exception should occur")
    exception.getMessage shouldEqual "You cannot use scale and wipe at the same time."
  }

  // FIXME (3456): breaks – why?
  ignore("killTasks with wipe delegates to taskKiller with wipe value") {
    import scala.concurrent.ExecutionContext.Implicits.global

    Given("a task that shall be killed")
    val app1 = "/my/app-1".toRootPath
    val taskId1 = Task.Id.forApp(app1).idString
    val body = s"""{"ids": ["$taskId1"]}"""
    val bodyBytes = body.toCharArray.map(_.toByte)
    val task1 = MarathonTestHelper.runningTask(taskId1)

    config.zkTimeoutDuration returns 5.seconds
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.forTasks(task1)
    taskTracker.appTasks(app1) returns Future.successful(Seq(task1))
    groupManager.app(app1) returns Future.successful(Some(AppDefinition(app1)))

    When("we send the request")
    val response = taskResource.killTasks(scale = false, force = false, wipe = true, body = bodyBytes, auth.request)

    Then("The response should be OK")
    response.getStatus shouldEqual 200

    And("the taskKiller receives the wipe flag")
    verify(taskKiller).kill(eq(app1), any, eq(true))

    And("nothing else should be called on the TaskKiller")
    noMoreInteractions(taskKiller)
  }

  test("killTask without authentication is denied when the affected app exists") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request
    val appId = "/my/app".toRootPath
    val taskId1 = Task.Id.forApp(appId).idString
    val taskId2 = Task.Id.forApp(appId).idString
    val taskId3 = Task.Id.forApp(appId).idString
    val body = s"""{"ids": ["$taskId1", "$taskId2", "$taskId3"]}""".getBytes

    Given("the app exists")
    groupManager.app(appId) returns Future.successful(Some(AppDefinition(appId)))

    When(s"kill task is called")
    val killTasks = taskResource.killTasks(scale = true, force = false, wipe = false, body, req)
    Then("we receive a NotAuthenticated response")
    killTasks.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("killTask without authentication is not allowed when the affected app does not exist") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request
    val appId = "/my/app".toRootPath
    val taskId1 = Task.Id.forApp(appId).idString
    val taskId2 = Task.Id.forApp(appId).idString
    val taskId3 = Task.Id.forApp(appId).idString
    val body = s"""{"ids": ["$taskId1", "$taskId2", "$taskId3"]}""".getBytes

    Given("the app does not exist")
    groupManager.app(appId) returns Future.successful(None)

    When(s"kill task is called")
    val killTasks = taskResource.killTasks(scale = true, force = false, wipe = false, body, req)
    Then("we receive a NotAuthenticated response")
    killTasks.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("indexTxt and IndexJson without authentication aren't allowed") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request

    When(s"the index as json is fetched")
    val running = taskResource.indexJson("status", Collections.emptyList(), req)
    Then("we receive a NotAuthenticated response")
    running.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"one index as txt is fetched")
    val cancel = taskResource.indexTxt(req)
    Then("we receive a NotAuthenticated response")
    cancel.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied if the affected app exists") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    val appId = "/my/app".toRootPath
    val taskId1 = Task.Id.forApp(appId).idString
    val taskId2 = Task.Id.forApp(appId).idString
    val taskId3 = Task.Id.forApp(appId).idString
    val body = s"""{"ids": ["$taskId1", "$taskId2", "$taskId3"]}""".getBytes

    taskKiller = new TaskKiller(taskTracker, stateOpProcessor, groupManager, service, config, auth.auth, auth.auth)
    taskResource = new TasksResource(
      service,
      taskTracker,
      taskKiller,
      config,
      groupManager,
      healthCheckManager,
      auth.auth,
      auth.auth
    )

    Given("the app exists")
    groupManager.app(appId) returns Future.successful(Some(AppDefinition(appId)))
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.empty

    When(s"kill task is called")
    val killTasks = taskResource.killTasks(scale = false, force = false, wipe = false, body, req)
    Then("we receive a not authorized response")
    killTasks.getStatus should be(auth.UnauthorizedStatus)
  }

  test("killTasks fails for invalid taskId") {
    Given("a valid and an invalid taskId")
    val app1 = "/my/app-1".toRootPath
    val taskId1 = Task.Id.forApp(app1).idString
    val body = s"""{"ids": ["$taskId1", "invalidTaskId"]}"""
    val bodyBytes = body.toCharArray.map(_.toByte)

    When("we ask to kill those two tasks")
    val ex = intercept[BadRequestException] {
      taskResource.killTasks(scale = false, force = false, wipe = false, body = bodyBytes, auth.request)
    }

    Then("An exception should be thrown that points to the invalid taskId")
    ex.getMessage should include ("invalidTaskId")

    And("the taskKiller should not be called at all")
    verifyNoMoreInteractions(taskKiller)
  }

  var service: MarathonSchedulerService = _
  var taskTracker: TaskTracker = _
  var stateOpProcessor: TaskStateOpProcessor = _
  var taskKiller: TaskKiller = _
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var healthCheckManager: HealthCheckManager = _
  var taskResource: TasksResource = _
  var auth: TestAuthFixture = _
  implicit var identity: Identity = _

  before {
    auth = new TestAuthFixture
    service = mock[MarathonSchedulerService]
    taskTracker = mock[TaskTracker]
    stateOpProcessor = mock[TaskStateOpProcessor]
    taskKiller = mock[TaskKiller]
    config = mock[MarathonConf]
    groupManager = mock[GroupManager]
    healthCheckManager = mock[HealthCheckManager]
    identity = mock[Identity]
    taskResource = new TasksResource(
      service,
      taskTracker,
      taskKiller,
      config,
      groupManager,
      healthCheckManager,
      auth.auth,
      auth.auth
    )
  }

}
