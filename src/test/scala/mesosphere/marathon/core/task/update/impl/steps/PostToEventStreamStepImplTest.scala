package mesosphere.marathon.core.task.update.impl.steps

import akka.event.EventStream
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.{ MarathonTaskStatus, TaskStatusUpdateTestHelper }
import mesosphere.marathon.event.{ MarathonEvent, MesosStatusUpdateEvent }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.{ CaptureEvents, CaptureLogEvents }
import org.apache.mesos.Protos.{ SlaveID, TaskState, TaskStatus }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.collection.immutable.Seq

class PostToEventStreamStepImplTest extends FunSuite with Matchers with GivenWhenThen with ScalaFutures {
  test("name") {
    new Fixture().step.name should be ("postTaskStatusEvent")
  }

  test("process running notification of staged task") {
    Given("an existing STAGED task")
    val f = new Fixture
    val existingTask = stagedMarathonTask

    When("we receive a running status update")
    val status = runningTaskStatus
    val taskUpdate = TaskStatusUpdateTestHelper.taskUpdateFor(existingTask, MarathonTaskStatus(status), updateTimestamp).wrapped
    val (logs, events) = f.captureLogAndEvents {
      f.step.processUpdate(taskUpdate).futureValue
    }

    Then("the appropriate event is posted")
    events should have size 1
    events should be (Seq(
      MesosStatusUpdateEvent(
        slaveId = slaveId.getValue,
        taskId = taskId,
        taskStatus = status.getState.name,
        message = taskStatusMessage,
        appId = appId,
        host = host,
        ipAddresses = Some(Seq(ipAddress)),
        ports = portsList,
        version = version.toString,
        timestamp = updateTimestamp.toString
      )
    ))
    And("only sending event info gets logged")
    logs should have size 1
    logs.map(_.toString) should be (Seq(
      s"[INFO] Sending event notification for $taskId of app [$appId]: ${status.getState}"
    ))
  }

  test("ignore running notification of already running task") {
    Given("an existing RUNNING task")
    val f = new Fixture
    val existingTask = MarathonTestHelper.runningTaskForApp(appId, startedAt = 100)

    When("we receive a running update")
    val status = runningTaskStatus
    val taskUpdate = TaskStatusUpdateTestHelper.taskUpdateFor(existingTask, MarathonTaskStatus(status), updateTimestamp).wrapped
    val (logs, events) = f.captureLogAndEvents {
      f.step.processUpdate(taskUpdate).futureValue
    }

    Then("no event is posted to the event stream")
    events should be (empty)
    And("and nothing of importance is logged")
    logs.filter(_.getLevel != Level.DEBUG) should be (empty)
  }

  test("terminate existing task with TASK_ERROR") { testExistingTerminatedTask(TaskState.TASK_ERROR) }
  test("terminate existing task with TASK_FAILED") { testExistingTerminatedTask(TaskState.TASK_FAILED) }
  test("terminate existing task with TASK_FINISHED") { testExistingTerminatedTask(TaskState.TASK_FINISHED) }
  test("terminate existing task with TASK_KILLED") { testExistingTerminatedTask(TaskState.TASK_KILLED) }
  test("terminate existing task with TASK_LOST") { testExistingTerminatedTask(TaskState.TASK_LOST) }

  private[this] def testExistingTerminatedTask(terminalTaskState: TaskState): Unit = {
    Given("an existing task")
    val f = new Fixture
    val existingTask = stagedMarathonTask

    When("we receive a terminal status update")
    val status = runningTaskStatus.toBuilder.setState(terminalTaskState).clearContainerStatus().build()
    val taskUpdate = TaskStatusUpdateTestHelper.taskUpdateFor(existingTask, MarathonTaskStatus(status), updateTimestamp).wrapped
    val (logs, events) = f.captureLogAndEvents {
      f.step.processUpdate(taskUpdate).futureValue
    }

    Then("the appropriate event is posted")
    events should have size 1
    events should be (Seq(
      MesosStatusUpdateEvent(
        slaveId = slaveId.getValue,
        taskId = taskId,
        taskStatus = status.getState.name,
        message = taskStatusMessage,
        appId = appId,
        host = host,
        ipAddresses = None,
        ports = portsList,
        version = version.toString,
        timestamp = updateTimestamp.toString
      )
    ))
    And("only sending event info gets logged")
    logs should have size 1
    logs.map(_.toString) should be (Seq(
      s"[INFO] Sending event notification for $taskId of app [$appId]: ${status.getState}"
    ))
  }

  private[this] val slaveId = SlaveID.newBuilder().setValue("slave1")
  private[this] val appId = PathId("/test")
  private[this] val taskId = Task.Id.forApp(appId)
  private[this] val host = "some.host.local"
  private[this] val ipAddress = MarathonTestHelper.mesosIpAddress("127.0.0.1")
  private[this] val portsList = Seq(10, 11, 12)
  private[this] val version = Timestamp(1)
  private[this] val updateTimestamp = Timestamp(100)
  private[this] val taskStatusMessage = "some update"

  private[this] val runningTaskStatus =
    TaskStatus
      .newBuilder()
      .setState(TaskState.TASK_RUNNING)
      .setTaskId(taskId.mesosTaskId)
      .setSlaveId(slaveId)
      .setMessage(taskStatusMessage)
      .setContainerStatus(
        MarathonTestHelper.containerStatusWithNetworkInfo(MarathonTestHelper.networkInfoWithIPAddress(ipAddress))
      )
      .build()

  import MarathonTestHelper.Implicits._
  private[this] val stagedMarathonTask =
    MarathonTestHelper.stagedTask(taskId.idString, appVersion = version)
      .withAgentInfo(_.copy(host = host))
      .withHostPorts(portsList)

  class Fixture {
    val eventStream = new EventStream()
    val captureEvents = new CaptureEvents(eventStream)

    def captureLogAndEvents(block: => Unit): (Vector[ILoggingEvent], Seq[MarathonEvent]) = {
      var logs: Vector[ILoggingEvent] = Vector.empty
      val events = captureEvents.forBlock {
        logs = CaptureLogEvents.forBlock {
          block
        }
      }

      (logs, events)
    }

    val step = new PostToEventStreamStepImpl(eventStream)
  }
}
