package mesosphere.marathon.core.task.update.impl.steps

import akka.actor.ActorSystem
import akka.event.EventStream
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.state.MarathonTaskStatus
import mesosphere.marathon.core.task.{ Task, TaskStateOp }
import mesosphere.marathon.core.event.{ MarathonEvent, MesosStatusUpdateEvent }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.{ CaptureEvents, CaptureLogEvents }
import org.apache.mesos.Protos.{ SlaveID, TaskState, TaskStatus }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, FunSuite, GivenWhenThen, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class PostToEventStreamStepImplTest extends FunSuite
    with Matchers with GivenWhenThen with ScalaFutures with BeforeAndAfterAll {
  val system = ActorSystem()
  override def afterAll(): Unit = {
    Await.result(system.terminate(), Duration.Inf)
  }
  test("name") {
    new Fixture(system).step.name should be ("postTaskStatusEvent")
  }

  test("process running notification of staged task") {
    Given("an existing STAGED task")
    val f = new Fixture(system)
    val existingTask = stagedMarathonTask

    When("we receive a running status update")
    val status = runningTaskStatus
    val taskUpdate = TaskStatusUpdateTestHelper.taskUpdateFor(existingTask, MarathonTaskStatus(status), status, updateTimestamp).wrapped
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
    logs.map(_.toString) should contain (
      s"[INFO] Sending event notification for $taskId of app [$appId]: ${status.getState}"
    )
  }

  test("ignore running notification of already running task") {
    Given("an existing RUNNING task")
    val f = new Fixture(system)
    val existingTask = MarathonTestHelper.runningTaskForApp(appId, startedAt = 100)

    When("we receive a running update")
    val status = runningTaskStatus
    val stateOp = TaskStateOp.MesosUpdate(existingTask, status, updateTimestamp)
    val stateChange = existingTask.update(stateOp)
    val taskChanged = TaskChanged(stateOp, stateChange)
    val (logs, events) = f.captureLogAndEvents {
      f.step.processUpdate(taskChanged).futureValue
    }

    Then("no event is posted to the event stream")
    events should be (empty)
    And("and nothing of importance is logged")
    logs.filter(l => l.getLevel != Level.DEBUG && l.getMessage.contains(appId)) should be (empty)
  }

  test("terminate existing task with TASK_ERROR") { testExistingTerminatedTask(TaskState.TASK_ERROR) }
  test("terminate existing task with TASK_FAILED") { testExistingTerminatedTask(TaskState.TASK_FAILED) }
  test("terminate existing task with TASK_FINISHED") { testExistingTerminatedTask(TaskState.TASK_FINISHED) }
  test("terminate existing task with TASK_KILLED") { testExistingTerminatedTask(TaskState.TASK_KILLED) }
  test("terminate existing task with TASK_LOST") { testExistingTerminatedTask(TaskState.TASK_LOST) }

  private[this] def testExistingTerminatedTask(terminalTaskState: TaskState): Unit = {
    Given("an existing task")
    val f = new Fixture(system)
    val existingTask = stagedMarathonTask

    When("we receive a terminal status update")
    val status = runningTaskStatus.toBuilder.setState(terminalTaskState).clearContainerStatus().build()
    val stateOp = TaskStateOp.MesosUpdate(existingTask, status, updateTimestamp)
    val stateChange = existingTask.update(stateOp)
    val taskUpdate = TaskChanged(stateOp, stateChange)
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
    logs.map(_.toString) should contain (
      s"[INFO] Sending event notification for $taskId of app [$appId]: ${status.getState}"
    )
  }

  private[this] val slaveId = SlaveID.newBuilder().setValue("slave1")
  private[this] val appId = PathId("/test")
  private[this] val taskId = Task.Id.forRunSpec(appId)
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

  class Fixture(system: ActorSystem) {
    val eventStream = new EventStream(system)
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

    val step = new PostToEventStreamStepImpl(eventStream, ConstantClock(Timestamp(100)))
  }
}
