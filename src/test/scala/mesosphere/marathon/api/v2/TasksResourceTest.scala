package mesosphere.marathon.api.v2

import java.util.Collections

import mesosphere.marathon.api.{ TaskKiller, TestAuthFixture }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ GroupManager, PathId, Timestamp }
import mesosphere.marathon.tasks.{ MarathonTasks, TaskIdUtil, TaskTracker }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, MarathonSpec }
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos._
import mesosphere.util.Mockito
import org.mockito.Matchers.{ eq => equalTo }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.duration._

class TasksResourceTest extends MarathonSpec with GivenWhenThen with Matchers with Mockito {

  test("killTasks") {
    val body = """{"ids": ["task-1", "task-2"]}"""
    val bodyBytes = body.toCharArray.map(_.toByte)
    val taskId1 = "task-1"
    val taskId2 = "task-2"
    val slaveId = SlaveID("some slave ID")
    val now = Timestamp.now()
    val task1 = MarathonTasks.makeTask(
      taskId1, "host", ports = Nil, attributes = Nil, version = Timestamp.now(), now = now,
      slaveId = slaveId
    )
    val task2 = MarathonTasks.makeTask(
      taskId2, "host", ports = Nil, attributes = Nil, version = Timestamp.now(), now = now,
      slaveId = slaveId
    )
    val app1 = PathId("/my/app-1")
    val app2 = PathId("/my/app-2")

    config.zkTimeoutDuration returns 5.seconds
    taskIdUtil.appId(taskId1) returns app1
    taskIdUtil.appId(taskId2) returns app2
    taskTracker.fetchTask(app1, taskId1) returns Some(task1)
    taskTracker.fetchTask(app2, taskId2) returns Some(task2)

    val response = taskResource.killTasks(scale = false, body = bodyBytes, auth.request, auth.response)
    response.getStatus shouldEqual 200
    verify(taskIdUtil, atLeastOnce).appId(taskId1)
    verify(taskIdUtil, atLeastOnce).appId(taskId2)
    verify(taskKiller).kill(eq(app1), any, force = eq(true))
    verify(taskKiller).kill(eq(app2), any, force = eq(true))
    noMoreInteractions(taskKiller)
  }

  test("access without authentication is denied") {
    Given("An unauthenticated request")
    auth.authenticated = false
    val req = auth.request
    val resp = auth.response
    val body = """{ "ids": ["a", "b", "c"] }""".getBytes

    When(s"the index as json is fetched")
    val running = taskResource.indexJson("status", Collections.emptyList(), req, resp)
    Then("we receive a NotAuthenticated response")
    running.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"one index as txt is fetched")
    val cancel = taskResource.indexTxt(req, resp)
    Then("we receive a NotAuthenticated response")
    cancel.getStatus should be(auth.NotAuthenticatedStatus)

    When(s"kill task is called")
    val killTasks = taskResource.killTasks(true, body, req, resp)
    Then("we receive a NotAuthenticated response")
    killTasks.getStatus should be(auth.NotAuthenticatedStatus)
  }

  test("access without authorization is denied") {
    Given("An unauthorized request")
    auth.authenticated = true
    auth.authorized = false
    val req = auth.request
    val resp = auth.response
    val body = """{ "ids": ["a", "b", "c"] }""".getBytes

    When(s"kill task is called")
    val killTasks = taskResource.killTasks(true, body, req, resp)
    Then("we receive a not authorized response")
    killTasks.getStatus should be(auth.UnauthorizedStatus)
  }

  var service: MarathonSchedulerService = _
  var taskTracker: TaskTracker = _
  var taskKiller: TaskKiller = _
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var healthCheckManager: HealthCheckManager = _
  var taskResource: TasksResource = _
  var taskIdUtil: TaskIdUtil = _
  var auth: TestAuthFixture = _

  before {
    auth = new TestAuthFixture
    service = mock[MarathonSchedulerService]
    taskTracker = mock[TaskTracker]
    taskKiller = mock[TaskKiller]
    config = mock[MarathonConf]
    groupManager = mock[GroupManager]
    healthCheckManager = mock[HealthCheckManager]
    taskIdUtil = mock[TaskIdUtil]
    taskResource = new TasksResource(
      service,
      taskTracker,
      taskKiller,
      config,
      groupManager,
      healthCheckManager,
      taskIdUtil,
      auth.auth,
      auth.auth
    )
  }
}
