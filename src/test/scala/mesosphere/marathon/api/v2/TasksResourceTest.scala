package mesosphere.marathon.api.v2

import mesosphere.marathon.api.TaskKiller
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ GroupManager, PathId, Timestamp }
import mesosphere.marathon.tasks.{ MarathonTasks, TaskIdUtil, TaskTracker }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, MarathonSpec }
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos._
import org.mockito.Matchers.{ any, eq => equalTo }
import org.mockito.Mockito._
import org.scalatest.Matchers

import scala.concurrent.duration._

class TasksResourceTest extends MarathonSpec with Matchers {

  var service: MarathonSchedulerService = _
  var taskTracker: TaskTracker = _
  var taskKiller: TaskKiller = _
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var healthCheckManager: HealthCheckManager = _
  var taskResource: TasksResource = _
  var taskIdUtil: TaskIdUtil = _

  before {
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
      taskIdUtil
    )
  }

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

    when(config.zkTimeoutDuration).thenReturn(5.seconds)
    when(taskIdUtil.appId(taskId1)).thenReturn(app1)
    when(taskIdUtil.appId(taskId2)).thenReturn(app2)
    when(taskTracker.fetchTask(app1, taskId1)).thenReturn(Some(task1))
    when(taskTracker.fetchTask(app2, taskId2)).thenReturn(Some(task2))

    val response = taskResource.killTasks(scale = false, body = bodyBytes)
    response.getStatus shouldEqual 200
    verify(taskIdUtil, atLeastOnce).appId(taskId1)
    verify(taskIdUtil, atLeastOnce).appId(taskId2)
    verify(taskKiller, times(1)).kill(equalTo(app1), any(), force = equalTo(true))
    verify(taskKiller, times(1)).kill(equalTo(app2), any(), force = equalTo(true))
    verifyNoMoreInteractions(taskKiller)
  }

}
