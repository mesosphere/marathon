package mesosphere.marathon.health

import akka.event.EventStream
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.tasks.TaskIdUtil
import org.apache.mesos.Protos

import scala.concurrent.Await
import scala.concurrent.duration._

class DelegatingHealthCheckManagerTest extends MarathonSpec {

  var hcManager: DelegatingHealthCheckManager = _
  var taskIdUtil: TaskIdUtil = _

  before {
    taskIdUtil = new TaskIdUtil
    hcManager = new DelegatingHealthCheckManager(mock[EventStream], taskIdUtil)
  }

  test("Add") {
    val healthCheck = HealthCheck()
    hcManager.add("test".toRootPath, healthCheck)
    assert(hcManager.list("test".toRootPath).size == 1)
  }

  test("Update") {
    val appId = "test".toRootPath
    val taskId = taskIdUtil.newTaskId(appId)
    val taskStatus = Protos.TaskStatus.newBuilder
      .setTaskId(taskId)
      .setState(Protos.TaskState.TASK_RUNNING)
      .setHealthy(false)
      .build

    val healthCheck = HealthCheck(protocol = Protocol.COMMAND)
    hcManager.add(appId, healthCheck)

    Await.result(hcManager.status(appId, taskId.getValue), 5.seconds) match {
      case List(None) =>
      case _          => fail()
    }

    hcManager.update(taskStatus.toBuilder.setHealthy(false).build, "")

    Await.result(hcManager.status(appId, taskId.getValue), 5.seconds) match {
      case List(Some(health)) =>
        assert(health.lastFailure.isDefined)
        assert(health.lastSuccess.isEmpty)
      case _ => fail()
    }

    hcManager.update(taskStatus.toBuilder.setHealthy(true).build, "")

    Await.result(hcManager.status(appId, taskId.getValue), 5.seconds) match {
      case List(Some(health)) =>
        assert(health.lastFailure.isDefined)
        assert(health.lastSuccess.isDefined)
        assert(health.lastSuccess > health.lastFailure)
      case _ => fail()
    }

  }

}