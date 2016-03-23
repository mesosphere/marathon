package mesosphere.marathon.api.v2

import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{ JsonTestHelper, TaskKiller, TestAuthFixture }
import mesosphere.marathon.core.appinfo.EnrichedTask
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, TaskTracker }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.plugin.auth.Identity
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ Group, GroupManager, PathId, _ }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ BadRequestException, MarathonConf, MarathonSchedulerService, MarathonSpec, MarathonTestHelper }
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
    taskKiller.kill(any, any, any)(any) returns Future.successful(toKill)
    groupManager.app(appId.toRootPath) returns Future.successful(Some(AppDefinition(appId.toRootPath)))

    val response = appsTaskResource.deleteMany(appId, host, scale = false, force = false, wipe = false, auth.request)
    response.getStatus shouldEqual 200
    JsonTestHelper
      .assertThatJsonString(response.getEntity.asInstanceOf[String])
      .correspondsToJsonOf(Json.obj("tasks" -> toKill))
  }

  test("deleteMany with scale and wipe fails") {
    val appId = "/my/app"
    val host = "host"

    val exception = intercept[BadRequestException] {
      appsTaskResource.deleteMany(appId, host, scale = true, force = false, wipe = true, auth.request)
    }
    exception.getMessage shouldEqual "You cannot use scale and wipe at the same time."
  }

  test("deleteMany with wipe delegates to taskKiller with wipe value") {
    val appId = "/my/app"
    val host = "host"
    taskKiller.kill(any, any, any)(any) returns Future.successful(Iterable.empty[Task])

    val response = appsTaskResource.deleteMany(appId, host, scale = false, force = false, wipe = true, auth.request)
    response.getStatus shouldEqual 200
    verify(taskKiller).kill(any, any, eq(true))(any)
  }

  test("deleteOne") {
    import MarathonTestHelper.Implicits._

    import scala.concurrent.ExecutionContext.Implicits.global
    val appId = PathId("/my/app")
    val slaveId = SlaveID("some slave ID")
    val task1 = MarathonTestHelper.mininimalTask(appId).withAgentInfo(_.copy(agentId = Some(slaveId.value)))
    val task2 = MarathonTestHelper.mininimalTask(appId).withAgentInfo(_.copy(agentId = Some(slaveId.value)))
    val toKill = Set(task1)

    config.zkTimeoutDuration returns 5.seconds
    taskTracker.appTasks(appId) returns Future.successful(Set(task1, task2))
    taskKiller.kill(any, any, any)(any) returns Future.successful(toKill)
    groupManager.app(appId) returns Future.successful(Some(AppDefinition(appId)))

    val response = appsTaskResource.deleteOne(
      appId.toString, task1.taskId.idString, scale = false, force = false, wipe = false, auth.request
    )
    response.getStatus shouldEqual 200
    JsonTestHelper
      .assertThatJsonString(response.getEntity.asInstanceOf[String])
      .correspondsToJsonOf(Json.obj("task" -> toKill.head))
    verify(taskKiller).kill(equalTo(appId), any, any)(any)
    verifyNoMoreInteractions(taskKiller)
  }

  test("deleteOne with scale and wipe fails") {
    val appId = "/my/app"
    val id = "task-1"

    val exception = intercept[BadRequestException] {
      appsTaskResource.deleteOne(appId, id, scale = true, force = false, wipe = true, auth.request)
    }
    exception.getMessage shouldEqual "You cannot use scale and wipe at the same time."
  }

  test("deleteOne with wipe delegates to taskKiller with wipe value") {
    import MarathonTestHelper.Implicits._

    import scala.concurrent.ExecutionContext.Implicits.global
    val appId = PathId("/my/app")
    val slaveId = SlaveID("some slave ID")
    val task1 = MarathonTestHelper.mininimalTask(appId).withAgentInfo(_.copy(agentId = Some(slaveId.value)))
    val task2 = MarathonTestHelper.mininimalTask(appId).withAgentInfo(_.copy(agentId = Some(slaveId.value)))
    val toKill = Set(task1)

    config.zkTimeoutDuration returns 5.seconds
    taskTracker.appTasks(appId) returns Future.successful(Set(task1, task2))
    taskKiller.kill(any, any, any)(any) returns Future.successful(toKill)
    groupManager.app(appId) returns Future.successful(Some(AppDefinition(appId)))

    val response = appsTaskResource.deleteOne(
      appId.toString, task1.taskId.idString, scale = false, force = false, wipe = true, auth.request
    )
    response.getStatus shouldEqual 200
    JsonTestHelper
      .assertThatJsonString(response.getEntity.asInstanceOf[String])
      .correspondsToJsonOf(Json.obj("task" -> toKill.head))
    verify(taskKiller).kill(equalTo(appId), any, org.mockito.Matchers.eq(true))(any)
    verifyNoMoreInteractions(taskKiller)
  }

  test("get tasks") {
    val appId = PathId("/my/app")

    val task1 = MarathonTestHelper.mininimalTask("task1")
    val task2 = MarathonTestHelper.mininimalTask("task2")

    config.zkTimeoutDuration returns 5.seconds
    taskTracker.tasksByAppSync returns TaskTracker.TasksByApp.of(TaskTracker.AppTasks.forTasks(appId, Iterable(task1, task2)))
    healthCheckManager.statuses(appId) returns Future.successful(collection.immutable.Map.empty)
    groupManager.app(appId) returns Future.successful(Some(AppDefinition(appId)))

    val response = appsTaskResource.indexJson("/my/app", auth.request)
    response.getStatus shouldEqual 200
    def toEnrichedTask(task: Task): EnrichedTask = {
      EnrichedTask(
        appId = appId,
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
    groupManager.rootGroup() returns Future.successful(Group.empty)

    When(s"the indexJson is fetched")
    val indexJson = appsTaskResource.indexJson("", req)
    Then("we receive a NotAuthenticated response")
    indexJson.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"the index as txt is fetched")
    val indexTxt = appsTaskResource.indexTxt("", req)
    Then("we receive a NotAuthenticated response")
    indexTxt.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"One task is deleted")
    val deleteOne = appsTaskResource.deleteOne("appId", "taskId", false, false, false, req)
    Then("we receive a NotAuthenticated response")
    deleteOne.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"multiple tasks are deleted")
    val deleteMany = appsTaskResource.deleteMany("appId", "host", false, false, false, req)
    Then("we receive a NotAuthenticated response")
    deleteMany.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access to indexJson without authorization leads to a 404 if the app does not exist") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    Given("the app does not exist")
    groupManager.app("/app".toRootPath) returns Future.successful(None)

    When(s"the indexJson is fetched")
    val indexJson = appsTaskResource.indexJson("/app", req)
    Then("we receive a 404")
    indexJson.getStatus should be(404)
  }

  test("access to indexJson without authorization is not allowed if the app exists") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    Given("the app exists")
    groupManager.app("/app".toRootPath) returns Future.successful(Some(AppDefinition("/app".toRootPath)))

    When(s"the indexJson is fetched")
    val indexJson = appsTaskResource.indexJson("/app", req)
    Then("we receive a not authorized response")
    indexJson.getStatus should be(auth.UnauthorizedStatus)
  }

  test("access to indexJson without authorization leads to a 404 if the group does not exist") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    Given("the group does not exist")
    groupManager.group("/group".toRootPath) returns Future.successful(None)

    When(s"the indexJson is fetched")
    val indexJson = appsTaskResource.indexJson("/group/*", req)
    Then("we receive a 404")
    indexJson.getStatus should be(404)
  }

  test("access to indexJson without authorization is not allowed if the group exists") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    Given("the group exists")
    val groupPath = "/group".toRootPath
    groupManager.group(groupPath) returns Future.successful(Some(Group(groupPath)))

    When(s"the indexJson is fetched")
    val indexJson = appsTaskResource.indexJson("/group/*", req)
    Then("we receive a not authorized response")
    indexJson.getStatus should be(auth.UnauthorizedStatus)
  }

  test("access to indexTxt without authorization is not allowed if the app exists") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    Given("The app exists")
    groupManager.app("/app".toRootPath) returns Future.successful(Some(AppDefinition("/app".toRootPath)))

    When(s"the index as txt is fetched")
    val indexTxt = appsTaskResource.indexTxt("/app", req)
    Then("we receive a not authorized response")
    indexTxt.getStatus should be(auth.UnauthorizedStatus)
  }

  test("access to indexTxt without authorization leads to a 404 if the the app does not exist") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request

    Given("The app exists")
    groupManager.app("/app".toRootPath) returns Future.successful(None)

    When(s"the index as txt is fetched")
    val indexTxt = appsTaskResource.indexTxt("/app", req)
    Then("we receive a not authorized response")
    indexTxt.getStatus should be(404)
  }

  test("access to deleteOne without authorization is not allowed if the app exists") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    useRealTaskKiller()

    Given("The app exists")
    groupManager.app("/app".toRootPath) returns Future.successful(Some(AppDefinition("/app".toRootPath)))

    When(s"deleteOne is called")
    val deleteOne = appsTaskResource.deleteOne("app", "taskId", false, false, false, req)
    Then("we receive a not authorized response")
    deleteOne.getStatus should be(auth.UnauthorizedStatus)
  }

  test("access to deleteOne without authorization leads to a 404 if the the app does not exist") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    useRealTaskKiller()

    Given("The app exists")
    groupManager.app("/app".toRootPath) returns Future.successful(None)

    When(s"deleteOne is called")
    val deleteOne = appsTaskResource.deleteOne("app", "taskId", false, false, false, req)
    Then("we receive a not authorized response")
    deleteOne.getStatus should be(404)
  }

  test("access to deleteMany without authorization is not allowed if the app exists") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    useRealTaskKiller()

    Given("The app exists")
    groupManager.app("/app".toRootPath) returns Future.successful(Some(AppDefinition("/app".toRootPath)))

    When(s"deleteMany is called")
    val deleteMany = appsTaskResource.deleteMany("app", "host", false, false, false, req)
    Then("we receive a not authorized response")
    deleteMany.getStatus should be(auth.UnauthorizedStatus)
  }

  test("access to deleteMany without authorization leads to a 404 if the the app does not exist") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    useRealTaskKiller()

    Given("The app exists")
    groupManager.app("/app".toRootPath) returns Future.successful(None)

    When(s"deleteMany is called")
    val deleteMany = appsTaskResource.deleteMany("app", "host", false, false, false, req)
    Then("we receive a not authorized response")
    deleteMany.getStatus should be(404)
  }

  var service: MarathonSchedulerService = _
  var taskTracker: TaskTracker = _
  var stateOpProcessor: TaskStateOpProcessor = _
  var taskKiller: TaskKiller = _
  var healthCheckManager: HealthCheckManager = _
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var appsTaskResource: AppTasksResource = _
  var auth: TestAuthFixture = _
  implicit var identity: Identity = _

  before {
    auth = new TestAuthFixture
    service = mock[MarathonSchedulerService]
    taskTracker = mock[TaskTracker]
    stateOpProcessor = mock[TaskStateOpProcessor]
    taskKiller = mock[TaskKiller]
    healthCheckManager = mock[HealthCheckManager]
    config = mock[MarathonConf]
    groupManager = mock[GroupManager]
    identity = auth.identity
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

    config.zkTimeoutDuration returns 1.second
  }

  private[this] def useRealTaskKiller(): Unit = {
    taskKiller = new TaskKiller(taskTracker, stateOpProcessor, groupManager, service, config, auth.auth, auth.auth)
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
