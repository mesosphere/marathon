package mesosphere.marathon.core.task.tracker.impl.steps

import akka.event.EventStream
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.event.{ MesosStatusUpdateEvent, MarathonEvent }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.tasks.{ MarathonTasks, TaskIdUtil }
import mesosphere.marathon.test.{ CaptureLogEvents, CaptureEvents }
import org.apache.mesos.Protos.{ SlaveID, TaskState, TaskStatus }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.collection.JavaConverters._

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
    val (logs, events) = f.captureLogAndEvents {
      f.step.processUpdate(
        timestamp = updateTimestamp,
        appId = appId,
        task = existingTask,
        status = status
      ).futureValue
    }

    Then("the appropriate event is posted")
    events should have size 1
    events should be (Seq(
      MesosStatusUpdateEvent(
        slaveId = slaveId.getValue,
        taskId = taskId.getValue,
        taskStatus = status.getState.name,
        message = taskStatusMessage,
        appId = appId,
        host = host,
        ipAddresses = Nil,
        ports = portsList.asJava.asScala,
        version = version.toString,
        timestamp = updateTimestamp.toString
      )
    ))
    And("only sending event info gets logged")
    logs should have size 1
    logs.map(_.toString) should be (Seq(
      s"[INFO] Sending event notification for task [${taskId.getValue}] of app [$appId]: ${status.getState}"
    ))
  }

  test("ignore running notification of already running task") {
    Given("an existing RUNNING task")
    val f = new Fixture
    val existingTask = stagedMarathonTask.toBuilder.setStartedAt(100).build()

    When("we receive a running update")
    val status = runningTaskStatus
    val (logs, events) = f.captureLogAndEvents {
      f.step.processUpdate(
        timestamp = updateTimestamp,
        appId = appId,
        task = existingTask,
        status = status
      ).futureValue
    }

    Then("no event is posted to the event stream")
    events should be (empty)
    And("and nothing of importance is logged")
    logs.filter(_.getLevel != Level.DEBUG_INT) should be (empty)
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
    val status = runningTaskStatus.toBuilder.setState(terminalTaskState).build()
    val (logs, events) = f.captureLogAndEvents {
      f.step.processUpdate(
        timestamp = updateTimestamp,
        appId = appId,
        task = existingTask,
        status = status
      ).futureValue
    }

    Then("the appropriate event is posted")
    events should have size 1
    events should be (Seq(
      MesosStatusUpdateEvent(
        slaveId = slaveId.getValue,
        taskId = taskId.getValue,
        taskStatus = status.getState.name,
        message = taskStatusMessage,
        appId = appId,
        host = host,
        ipAddresses = Nil,
        ports = portsList.asJava.asScala,
        version = version.toString,
        timestamp = updateTimestamp.toString
      )
    ))
    And("only sending event info gets logged")
    logs should have size 1
    logs.map(_.toString) should be (Seq(
      s"[INFO] Sending event notification for task [${taskId.getValue}] of app [$appId]: ${status.getState}"
    ))
  }

  private[this] val slaveId = SlaveID.newBuilder().setValue("slave1")
  private[this] val appId = PathId("/test")
  private[this] val taskId = TaskIdUtil.newTaskId(appId)
  private[this] val host = "some.host.local"
  private[this] val portsList = Seq(10, 11, 12).map(java.lang.Integer.valueOf(_))
  private[this] val version = Timestamp(1)
  private[this] val updateTimestamp = Timestamp(100)
  private[this] val taskStatusMessage = "some update"

  private[this] val runningTaskStatus =
    TaskStatus
      .newBuilder()
      .setState(TaskState.TASK_RUNNING)
      .setTaskId(taskId)
      .setSlaveId(slaveId)
      .setMessage(taskStatusMessage)
      .build()

  private[this] val stagedMarathonTask =
    MarathonTask
      .newBuilder()
      .setId(taskId.getValue)
      .setHost(host)
      .addAllPorts(portsList.asJava)
      .setVersion(version.toString)
      .build()

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
