package mesosphere.marathon.health

import akka.actor._
import akka.event.EventStream
import akka.testkit.EventFilter
import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory
import mesosphere.marathon.{ MarathonConf, MarathonSpec }
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import mesosphere.util.Logging
import org.apache.mesos.state.InMemoryState
import org.apache.mesos.Protos

import scala.concurrent.Await
import scala.concurrent.duration._

class MarathonHealthCheckManagerTest extends MarathonSpec with Logging {

  var hcManager: MarathonHealthCheckManager = _

  implicit var system: ActorSystem = _

  before {
    val registry = new MetricRegistry

    system = ActorSystem(
      "test-system",
      ConfigFactory.parseString(
        """akka.loggers = ["akka.testkit.TestEventListener"]"""
      )
    )

    hcManager = new MarathonHealthCheckManager(
      system,
      mock[EventStream],
      new TaskTracker(new InMemoryState, mock[MarathonConf], registry)
    )
  }

  after {
    system.shutdown()
  }

  test("Add") {
    val healthCheck = HealthCheck()
    hcManager.add("test".toRootPath, healthCheck)
    assert(hcManager.list("test".toRootPath).size == 1)
  }

  test("Update") {
    val appId = "test".toRootPath
    val taskId = TaskIdUtil.newTaskId(appId)
    val taskStatus = Protos.TaskStatus.newBuilder
      .setTaskId(taskId)
      .setState(Protos.TaskState.TASK_RUNNING)
      .setHealthy(false)
      .build

    val healthCheck = HealthCheck(protocol = Protocol.COMMAND)
    hcManager.add(appId, healthCheck)

    val status1 = Await.result(hcManager.status(appId, taskId.getValue), 5.seconds)
    assert(status1 == Seq(None))

    // send unhealthy task status
    EventFilter.info(start = "Received health result: [", occurrences = 1).intercept {
      hcManager.update(taskStatus.toBuilder.setHealthy(false).build, "")
    }

    val Seq(Some(health2)) = Await.result(hcManager.status(appId, taskId.getValue), 5.seconds)
    assert(health2.lastFailure.isDefined)
    assert(health2.lastSuccess.isEmpty)

    // send healthy task status
    EventFilter.info(start = "Received health result: [", occurrences = 1).intercept {
      hcManager.update(taskStatus.toBuilder.setHealthy(true).build, "")
    }

    val Seq(Some(health3)) = Await.result(hcManager.status(appId, taskId.getValue), 5.seconds)
    assert(health3.lastFailure.isDefined)
    assert(health3.lastSuccess.isDefined)
    assert(health3.lastSuccess > health3.lastFailure)

  }

}
