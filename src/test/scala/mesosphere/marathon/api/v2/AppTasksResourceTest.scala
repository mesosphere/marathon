package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ JsonTestHelper, TaskKiller, TestAuthFixture }
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ Group, GroupManager, PathId, Timestamp }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonTestHelper, MarathonConf, MarathonSchedulerService, MarathonSpec }
import mesosphere.mesos.protos.SlaveID
import org.mockito.Matchers.{ eq => equalTo }
import org.mockito.Mockito._
import org.scalatest.{ GivenWhenThen, Matchers }
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.concurrent.duration._

class AppTasksResourceTest extends MarathonSpec with Matchers with GivenWhenThen with Mockito {

  test("deleteMany") {
    val appId = "/my/app"
    val host = "host"
    val toKill = Set(MarathonTestHelper.stagedTaskForApp(PathId(appId)))

    config.zkTimeoutDuration returns 5.seconds
    taskKiller.kill(any, any) returns Future.successful(toKill)

    val response = appsTaskResource.deleteMany(appId, host, scale = false, force = false, auth.request, auth.response)
    response.getStatus shouldEqual 200
    JsonTestHelper
      .assertThatJsonString(response.getEntity.asInstanceOf[String])
      .correspondsToJsonOf(Json.obj("tasks" -> toKill))
  }

  test("deleteOne") {
    val host = "host"
    val appId = PathId("/my/app")
    val slaveId = SlaveID("some slave ID")
    val now = Timestamp.now()
    val task1 = MarathonTestHelper.mininimalTask(appId).withAgentInfo(_.copy(agentId = Some(slaveId.value)))
    val task2 = MarathonTestHelper.mininimalTask(appId).withAgentInfo(_.copy(agentId = Some(slaveId.value)))
    val toKill = Set(task1)

    config.zkTimeoutDuration returns 5.seconds
    taskTracker.appTasksSync(appId) returns Set(task1, task2)
    taskKiller.kill(any, any) returns Future.successful(toKill)

    val response = appsTaskResource.deleteOne(
      appId.root, task1.taskId.idString, scale = false, force = false, auth.request, auth.response
    )
    response.getStatus shouldEqual 200
    JsonTestHelper
      .assertThatJsonString(response.getEntity.asInstanceOf[String])
      .correspondsToJsonOf(Json.obj("task" -> toKill.head))
    verify(taskKiller).kill(equalTo(appId.rootPath), any)
    verifyNoMoreInteractions(taskKiller)
  }

  test("get tasks") {
    val host = "host"
    val appId = PathId("/my/app")
    val slaveId = SlaveID("some slave ID")
    val now = Timestamp.now()

    val task1 = MarathonTestHelper.mininimalTask("task1")
    val task2 = MarathonTestHelper.mininimalTask("task2")

    config.zkTimeoutDuration returns 5.seconds
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.of(TaskTracker.AppTasks.forTasks(appId, Iterable(task1, task2)))
    healthCheckManager.statuses(appId) returns Future.successful(collection.immutable.Map.empty)

    val response = appsTaskResource.indexJson("/my/app", auth.request, auth.response)
    response.getStatus shouldEqual 200
    def toEnrichedTask(task: Task): EnrichedTask = {
      EnrichedTask(
        appId = PathId("/my/app"),
        task = task,
        healthCheckResults = Seq(),
        servicePorts = Seq()
      )
    }
    JsonTestHelper
      .assertThatJsonString(response.getEntity.asInstanceOf[String])
      .correspondsToJsonOf(Json.obj("tasks" -> Seq(task1, task2).map(toEnrichedTask)))
  }

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request
    val resp = auth.response
    groupManager.rootGroup() returns Future.successful(Group.empty)

    When(s"the indexJson is fetched")
    val indexJson = appsTaskResource.indexJson("", req, resp)
    Then("we receive a NotAuthenticated response")
    indexJson.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the index as txt is fetched")
    val indexTxt = appsTaskResource.indexTxt("", req, resp)
    Then("we receive a NotAuthenticated response")
    indexTxt.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"One task is deleted")
    val deleteOne = appsTaskResource.deleteOne("appId", "taskId", false, false, req, resp)
    Then("we receive a NotAuthenticated response")
    deleteOne.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"multiple tasks are deleted")
    val deleteMany = appsTaskResource.deleteMany("appId", "host", false, false, req, resp)
    Then("we receive a NotAuthenticated response")
    deleteMany.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    val resp = auth.response
    groupManager.rootGroup() returns Future.successful(Group.empty)

    When(s"the indexJson is fetched")
    val indexJson = appsTaskResource.indexJson("", req, resp)
    Then("we receive a not authorized response")
    indexJson.getStatus should be(auth.UnauthorizedStatus)

    When(s"the index as txt is fetched")
    val indexTxt = appsTaskResource.indexTxt("", req, resp)
    Then("we receive a not authorized response")
    indexTxt.getStatus should be(auth.UnauthorizedStatus)

    When(s"One task is deleted")
    val deleteOne = appsTaskResource.deleteOne("appId", "taskId", false, false, req, resp)
    Then("we receive a not authorized response")
    deleteOne.getStatus should be(auth.UnauthorizedStatus)

    When(s"multiple tasks are deleted")
    val deleteMany = appsTaskResource.deleteMany("appId", "host", false, false, req, resp)
    Then("we receive a not authorized response")
    deleteMany.getStatus should be(auth.UnauthorizedStatus)
  }

  var service: MarathonSchedulerService = _
  var taskTracker: TaskTracker = _
  var taskKiller: TaskKiller = _
  var healthCheckManager: HealthCheckManager = _
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var appsTaskResource: AppTasksResource = _
  var auth: TestAuthFixture = _

  before {
    auth = new TestAuthFixture
    service = mock[MarathonSchedulerService]
    taskTracker = mock[TaskTracker]
    taskKiller = mock[TaskKiller]
    healthCheckManager = mock[HealthCheckManager]
    config = mock[MarathonConf]
    groupManager = mock[GroupManager]
    appsTaskResource = new AppTasksResource(
      service,
      taskTracker,
      taskKiller,
      healthCheckManager,
      config,
      groupManager,
      auth.auth,
      auth.auth
    )
  }

}
