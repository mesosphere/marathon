package mesosphere.marathon.health

import scala.concurrent.ExecutionContext.Implicits.global
import akka.event.EventStream
import org.apache.mesos.Protos
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.PathId

class DelegatingHealthCheckManagerTest extends MarathonSpec {

  var hcManager: DelegatingHealthCheckManager = null

  before {
    hcManager = new DelegatingHealthCheckManager(mock[EventStream])
  }

  test("Add") {
    val healthCheck = HealthCheck()
    hcManager.add(PathId("test"), healthCheck)
    assert(hcManager.list(PathId("test")).size == 1)
  }

  test("Update") {
    val taskStatus = Protos.TaskStatus.newBuilder
      .setTaskId(Protos.TaskID.newBuilder.setValue("test.1234"))
      .setState(Protos.TaskState.TASK_RUNNING)
      .build

    hcManager.update(taskStatus, "")

    hcManager.status(PathId("test"), "test.1234") foreach {
      case List(Some(health)) =>
        assert(health.lastFailure.isEmpty)
        assert(health.lastSuccess.isEmpty)
    }

    hcManager.update(taskStatus.toBuilder.setHealthy(false).build, "")

    hcManager.status(PathId("test"), "test.1234") foreach {
      case List(Some(health)) =>
        assert(health.lastFailure.isDefined)
        assert(health.lastSuccess.isEmpty)
    }

    hcManager.update(taskStatus.toBuilder.setHealthy(true).build, "")

    hcManager.status(PathId("test"), "test.1234") foreach {
      case List(Some(health)) =>
        assert(health.lastFailure.isDefined)
        assert(health.lastSuccess.isDefined)
        assert(health.lastSuccess > health.lastFailure)
    }

  }

}