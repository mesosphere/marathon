package mesosphere.marathon.core.task.update.impl.steps

import akka.actor.ActorSystem
import akka.event.EventStream
import ch.qos.logback.classic.spi.ILoggingEvent
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.{ InstanceConversions, MarathonTestHelper }
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.{ MarathonTaskStatus, Task }
import mesosphere.marathon.core.event.{ InstanceChanged, InstanceHealthChanged, MarathonEvent, MesosStatusUpdateEvent }
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation, InstanceUpdated }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.{ CaptureEvents, CaptureLogEvents }
import org.apache.mesos.Protos.{ SlaveID, TaskState, TaskStatus }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, FunSuite, GivenWhenThen, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import org.apache.mesos

class PostToEventStreamStepImplTest extends FunSuite
    with Matchers with GivenWhenThen with ScalaFutures with BeforeAndAfterAll with InstanceConversions {
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
    val expectedInstanceStatus = InstanceStatus.Running

    When("we receive a running status update")
    val status = makeTaskStatus(existingTask.taskId, mesos.Protos.TaskState.TASK_RUNNING)
    val instanceChange = TaskStatusUpdateTestHelper.taskUpdateFor(existingTask, MarathonTaskStatus(status), status, updateTimestamp).wrapped
    val (logs, events) = f.captureLogAndEvents {
      f.step.process(instanceChange).futureValue
    }

    Then("the appropriate event is posted")
    events should have size 2
    events should be (Seq(
      InstanceChanged(
        instanceChange.instance.instanceId,
        instanceChange.runSpecVersion,
        instanceChange.runSpecId,
        expectedInstanceStatus,
        instanceChange.instance
      ),
      MesosStatusUpdateEvent(
        slaveId = slaveId.getValue,
        taskId = existingTask.taskId,
        taskStatus = expectedInstanceStatus.toMesosStateName,
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
      s"[INFO] Sending instance change event for ${instanceChange.instance.instanceId} of runSpec [$appId]: ${instanceChange.status}"
    )
  }

  test("ignore running notification of already running task") {
    Given("an existing RUNNING task")
    val task: Task = MarathonTestHelper.runningTaskForApp(appId, startedAt = 100)
    val existingInstance: Instance = task

    When("we receive a running update")
    val status = makeTaskStatus(task.taskId, mesos.Protos.TaskState.TASK_RUNNING)
    val stateOp = InstanceUpdateOperation.MesosUpdate(existingInstance, status, updateTimestamp)
    val stateChange = existingInstance.update(stateOp)

    Then("the effect is a noop")
    stateChange shouldBe a[InstanceUpdateEffect.Noop]
  }

  test("Send InstanceChangeHealthEvent, if the instance health changes") {
    Given("an existing RUNNING task")
    val f = new Fixture(system)

    val task: Task = MarathonTestHelper.runningTaskForApp(appId, startedAt = 100)
    val instance: Instance = task
    val healthyState = InstanceState(InstanceStatus.Running, Timestamp.now(), Timestamp.now(), Some(true))
    val unhealthyState = InstanceState(InstanceStatus.Running, Timestamp.now(), Timestamp.now(), Some(false))
    val healthyInstance = instance.copy(state = healthyState)
    val instanceChange: InstanceUpdated = InstanceUpdated(healthyInstance, Some(unhealthyState), trigger = None)

    When("we receive a health status changed")
    val (logs, events) = f.captureLogAndEvents {
      f.step.process(instanceChange).futureValue
    }

    Then("the effect is a noop")
    events should have size 3
    events.tail.head should be (
      InstanceHealthChanged(
        instanceChange.instance.instanceId,
        instanceChange.runSpecVersion,
        instanceChange.runSpecId,
        Some(true)
      )
    )
  }

  test("terminate staged task with TASK_ERROR") { testExistingTerminatedTask(TaskState.TASK_ERROR, stagedMarathonTask) }
  test("terminate staged task with TASK_FAILED") { testExistingTerminatedTask(TaskState.TASK_FAILED, stagedMarathonTask) }
  test("terminate staged task with TASK_FINISHED") { testExistingTerminatedTask(TaskState.TASK_FINISHED, stagedMarathonTask) }
  test("terminate staged task with TASK_KILLED") { testExistingTerminatedTask(TaskState.TASK_KILLED, stagedMarathonTask) }
  test("terminate staged task with TASK_LOST") { testExistingTerminatedTask(TaskState.TASK_LOST, stagedMarathonTask) }

  test("terminate staged resident task with TASK_ERROR") { testExistingTerminatedTask(TaskState.TASK_ERROR, residentStagedTask) }
  test("terminate staged resident task with TASK_FAILED") { testExistingTerminatedTask(TaskState.TASK_FAILED, residentStagedTask) }
  test("terminate staged resident task with TASK_FINISHED") { testExistingTerminatedTask(TaskState.TASK_FINISHED, residentStagedTask) }
  test("terminate staged resident task with TASK_KILLED") { testExistingTerminatedTask(TaskState.TASK_KILLED, residentStagedTask) }
  test("terminate staged resident task with TASK_LOST") { testExistingTerminatedTask(TaskState.TASK_LOST, residentStagedTask) }

  private[this] def testExistingTerminatedTask(terminalTaskState: TaskState, task: Task): Unit = {
    Given("an existing task")
    val f = new Fixture(system)
    val taskStatus = makeTaskStatus(task.taskId, terminalTaskState)
    val expectedInstanceStatus = MarathonTaskStatus(taskStatus)
    val helper = TaskStatusUpdateTestHelper.taskUpdateFor(task, expectedInstanceStatus, taskStatus, timestamp = updateTimestamp)

    When("we receive a terminal status update")
    val instanceChange = helper.wrapped
    val (logs, events) = f.captureLogAndEvents {
      f.step.process(instanceChange).futureValue
    }

    Then("the appropriate event is posted")
    events should have size 2
    events shouldEqual Seq(
      InstanceChanged(
        instanceChange.instance.instanceId,
        instanceChange.runSpecVersion,
        instanceChange.runSpecId,
        expectedInstanceStatus,
        instanceChange.instance
      ),
      MesosStatusUpdateEvent(
        slaveId = slaveId.getValue,
        taskId = task.taskId,
        taskStatus = expectedInstanceStatus.toMesosStateName,
        message = taskStatusMessage,
        appId = appId,
        host = host,
        ipAddresses = Some(Seq(ipAddress)),
        ports = portsList,
        version = task.version.get.toString,
        timestamp = updateTimestamp.toString
      )
    )
    And("only sending event info gets logged")
    logs.map(_.toString) should contain (
      s"[INFO] Sending instance change event for ${instanceChange.instance.instanceId} of runSpec [$appId]: ${instanceChange.status}"
    )
  }

  private[this] val slaveId = SlaveID.newBuilder().setValue("slave1")
  private[this] val appId = PathId("/test")
  private[this] val host = "some.host.local"
  private[this] val ipAddress = MarathonTestHelper.mesosIpAddress("127.0.0.1")
  private[this] val portsList = Seq(10, 11, 12)
  private[this] val version = Timestamp(1)
  private[this] val updateTimestamp = Timestamp(100)
  private[this] val taskStatusMessage = "some update"

  private[this] def makeTaskStatus(taskId: Task.Id, state: mesos.Protos.TaskState) =
    TaskStatus
      .newBuilder()
      .setState(state)
      .setTaskId(taskId.mesosTaskId)
      .setSlaveId(slaveId)
      .setMessage(taskStatusMessage)
      .setContainerStatus(
        MarathonTestHelper.containerStatusWithNetworkInfo(MarathonTestHelper.networkInfoWithIPAddress(ipAddress))
      )
      .build()

  import MarathonTestHelper.Implicits._
  private[this] val stagedMarathonTask =
    MarathonTestHelper.stagedTask(Task.Id.forRunSpec(appId), appVersion = version)
      .withAgentInfo(_.copy(host = host))
      .withHostPorts(portsList)

  private[this] val residentStagedTask =
    MarathonTestHelper.residentStagedTask(appId, Seq.empty[Task.LocalVolumeId]: _*)
      .withAgentInfo(_.copy(host = host))
      .withHostPorts(portsList)

  class Fixture(system: ActorSystem) {
    val eventStream = new EventStream(system)
    val captureEvents = new CaptureEvents(eventStream)

    def captureLogAndEvents(block: => Unit): (Vector[ILoggingEvent], Seq[MarathonEvent]) = {
      var logs: Vector[ILoggingEvent] = Vector.empty
      val events = captureEvents.forBlock {
        logs = CaptureLogEvents.forBlock { // linter:ignore:VariableAssignedUnusedValue
          block
        }
      }

      (logs, events)
    }

    val step = new PostToEventStreamStepImpl(eventStream, ConstantClock(Timestamp(100)))
  }
}
