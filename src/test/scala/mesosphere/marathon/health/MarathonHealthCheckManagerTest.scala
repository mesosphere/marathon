package mesosphere.marathon.health

import akka.actor._
import akka.event.EventStream
import akka.testkit.EventFilter
import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory
import mesosphere.marathon.{ MarathonConf, MarathonSpec }
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.state.PathId.StringPathId
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import mesosphere.util.Logging
import org.apache.mesos.state.InMemoryState
import org.apache.mesos.{ Protos => mesos }

import scala.concurrent.Await
import scala.concurrent.duration._

class MarathonHealthCheckManagerTest extends MarathonSpec with Logging {

  var hcManager: MarathonHealthCheckManager = _
  var taskTracker: TaskTracker = _

  implicit var system: ActorSystem = _

  before {
    val registry = new MetricRegistry

    system = ActorSystem(
      "test-system",
      ConfigFactory.parseString(
        """akka.loggers = ["akka.testkit.TestEventListener"]"""
      )
    )

    taskTracker = new TaskTracker(new InMemoryState, mock[MarathonConf], registry)

    hcManager = new MarathonHealthCheckManager(
      system,
      mock[EventStream],
      taskTracker
    )
  }

  after {
    system.shutdown()
  }

  test("Add") {
    val healthCheck = HealthCheck()
    val version = Timestamp(1024)
    hcManager.add("test".toRootPath, version, healthCheck)
    assert(hcManager.list("test".toRootPath).size == 1)
  }

  test("Update") {
    val appId = "test".toRootPath

    val taskId = TaskIdUtil.newTaskId(appId)

    val version = Timestamp(1024)

    val taskStatus = mesos.TaskStatus.newBuilder
      .setTaskId(taskId)
      .setState(mesos.TaskState.TASK_RUNNING)
      .setHealthy(false)
      .build

    val marathonTask = MarathonTask.newBuilder
      .setId(taskId.getValue)
      .setVersion(version.toString)
      .build

    val healthCheck = HealthCheck(protocol = Protocol.COMMAND, gracePeriod = 0.seconds)

    taskTracker.created(appId, marathonTask)
    taskTracker.running(appId, taskStatus)

    hcManager.add(appId, version, healthCheck)

    val status1 = Await.result(hcManager.status(appId, taskId.getValue), 5.seconds)
    assert(status1 == Seq(None))

    // send unhealthy task status
    EventFilter.info(start = "Received health result: [", occurrences = 1).intercept {
      hcManager.update(taskStatus.toBuilder.setHealthy(false).build, version)
    }

    val Seq(Some(health2)) = Await.result(hcManager.status(appId, taskId.getValue), 5.seconds)
    assert(health2.lastFailure.isDefined)
    assert(health2.lastSuccess.isEmpty)

    // send healthy task status
    EventFilter.info(start = "Received health result: [", occurrences = 1).intercept {
      hcManager.update(taskStatus.toBuilder.setHealthy(true).build, version)
    }

    val Seq(Some(health3)) = Await.result(hcManager.status(appId, taskId.getValue), 5.seconds)
    assert(health3.lastFailure.isDefined)
    assert(health3.lastSuccess.isDefined)
    assert(health3.lastSuccess > health3.lastFailure)

  }

}
