package mesosphere.marathon.api.v2

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.api.TaskKiller
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ Timestamp, GroupManager, PathId }
import mesosphere.marathon.tasks.{ MarathonTasks, TaskTracker }
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, MarathonSpec }
import org.mockito.Matchers.{ any, anyBoolean, eq => equalTo }
import org.mockito.Mockito._
import org.scalatest.Matchers

import scala.concurrent.Future
import scala.concurrent.duration._

class AppTasksResourceTest extends MarathonSpec with Matchers {

  var service: MarathonSchedulerService = _
  var taskTracker: TaskTracker = _
  var taskKiller: TaskKiller = _
  var healthCheckManager: HealthCheckManager = _
  var config: MarathonConf = _
  var groupManager: GroupManager = _
  var appsTaskResource: AppTasksResource = _

  before {
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
      groupManager
    )
  }

  test("deleteMany") {
    val appId = "/my/app"
    val host = "host"
    val toKill = Set(MarathonTask.getDefaultInstance)

    when(config.zkTimeoutDuration).thenReturn(5.seconds)
    when(taskKiller.kill(any[PathId](), any(), anyBoolean())).thenReturn(
      Future.successful(toKill))

    val response = appsTaskResource.deleteMany(appId, host, scale = false)
    response.getStatus shouldEqual 200
    response.getEntity shouldEqual Map("tasks" -> toKill)
  }

  test("deleteOne") {
    val host = "host"
    val appId = PathId("/my/app")
    val task1 = MarathonTasks.makeTask("task-1", host, ports = Nil, attributes = Nil, version = Timestamp.now())
    val task2 = MarathonTasks.makeTask("task-2", host, ports = Nil, attributes = Nil, version = Timestamp.now())
    val toKill = Set(task1)

    when(config.zkTimeoutDuration).thenReturn(5.seconds)
    when(taskTracker.get(appId)).thenReturn(Set(task1, task2))
    when(taskKiller.kill(any[PathId](), any(), anyBoolean())).thenReturn(
      Future.successful(toKill))

    val response = appsTaskResource.deleteOne(appId.root, task1.getId, scale = false)
    response.getStatus shouldEqual 200
    response.getEntity shouldEqual Map("task" -> toKill.head)
    verify(taskKiller, times(1)).kill(equalTo(appId.rootPath), any(), force = equalTo(true))
    verifyNoMoreInteractions(taskKiller)
  }

}
