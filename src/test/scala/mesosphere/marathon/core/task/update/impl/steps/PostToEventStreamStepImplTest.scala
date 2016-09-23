package mesosphere.marathon.core.task.update.impl.steps

import akka.actor.ActorSystem
import akka.event.EventStream
import ch.qos.logback.classic.spi.ILoggingEvent
import mesosphere.marathon.InstanceConversions
import mesosphere.marathon.builder.TestTaskBuilder
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.event.{ InstanceHealthChanged, MarathonEvent }
import mesosphere.marathon.core.instance.Instance.InstanceState
import mesosphere.marathon.core.instance.update.{ InstanceChangedEventsGenerator, InstanceUpdateEffect, InstanceUpdateOperation, InstanceChange, InstanceUpdated }
import mesosphere.marathon.core.instance.{ Instance, InstanceStatus }
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.{ MarathonTaskStatus, Task }
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.{ CaptureEvents, CaptureLogEvents, MarathonTestHelper }
import org.apache.mesos
import org.apache.mesos.Protos.{ SlaveID, TaskState, TaskStatus }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, FunSuite, GivenWhenThen, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration.Duration

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
    val helper = TaskStatusUpdateTestHelper.taskUpdateFor(existingTask, MarathonTaskStatus(status), status, updateTimestamp).wrapped
    val (logs, events) = f.captureLogAndEvents {
      f.step.process(helper).futureValue
    }

    Then("the appropriate event is posted")
    val expectedEvents = f.eventsGenerator.events(expectedInstanceStatus, helper.instance, Some(existingTask), updateTimestamp)
    events should have size 2
    events shouldEqual expectedEvents

    And("only sending event info gets logged")
    logs.map(_.toString) should contain (
      s"[INFO] Publishing events for ${helper.instance.instanceId} of runSpec [$appId]: ${helper.status}"
    )
  }

  test("ignore running notification of already running task") {
    Given("an existing RUNNING task")
    val f = new Fixture(system)
    val existingInstance: Instance = TestTaskBuilder.Creator.runningTaskForApp(appId, startedAt = 100)

    When("we receive a running update")
    val status = makeTaskStatus(existingInstance.instanceId, mesos.Protos.TaskState.TASK_RUNNING)
    val stateOp = InstanceUpdateOperation.MesosUpdate(existingInstance, status, updateTimestamp)
    val stateChange = existingInstance.update(stateOp)

    Then("the effect is a noop")
    stateChange shouldBe a[InstanceUpdateEffect.Noop]
  }

  test("send InstanceChangeHealthEvent, if the instance becomes healthy") {
    Given("an existing RUNNING task")
    val f = new Fixture(system)

    When("we process an update with changed health status")
    val instanceChange = f.nowHealthy()
    val (_, events) = f.captureLogAndEvents {
      f.step.process(instanceChange).futureValue
    }

    Then("an event is published stating that the instance is now healthy")
    events should have size 1
    events.head should be (
      f.healthChangedEvent(instanceChange.instance, healthy = true)
    )
  }

  test("send InstanceChangeHealthEvent, if the instance becomes unhealthy") {
    Given("an existing RUNNING task")
    val f = new Fixture(system)

    When("we process an update with changed health status")
    val instanceChange = f.nowUnhealthy()
    val (_, events) = f.captureLogAndEvents {
      f.step.process(instanceChange).futureValue
    }

    Then("an event is published stating that the instance is now unhealthy")
    events should have size 1
    events.head should be (
      f.healthChangedEvent(instanceChange.instance, healthy = false)
    )
  }

  test("don't send InstanceChangeHealthEvent, if the instance health state doesn't change") {
    Given("an existing RUNNING task")
    val f = new Fixture(system)

    When("we process an update without health status change")
    val instanceChange = f.noHealthChange()
    val (_, events) = f.captureLogAndEvents {
      f.step.process(instanceChange).futureValue
    }

    Then("no event is published")
    events should have size 0
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
    val expectedEvents = f.eventsGenerator.events(expectedInstanceStatus, helper.wrapped.instance, Some(task), updateTimestamp)
    events should have size 2
    events shouldEqual expectedEvents

    And("only sending event info gets logged")
    logs.map(_.toString) should contain (
      s"[INFO] Publishing events for ${instanceChange.instance.instanceId} of runSpec [$appId]: ${instanceChange.status}"
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

  private[this] def makeTaskStatus(taskId: Instance.Id, state: mesos.Protos.TaskState) =
    TaskStatus
      .newBuilder()
      .setState(state)
      .setTaskId(Task.Id.forInstanceId(taskId, None).mesosTaskId)
      .setSlaveId(slaveId)
      .setMessage(taskStatusMessage)
      .setContainerStatus(
        MarathonTestHelper.containerStatusWithNetworkInfo(MarathonTestHelper.networkInfoWithIPAddress(ipAddress))
      )
      .build()

  import MarathonTestHelper.Implicits._
  private[this] val stagedMarathonTask =
    TestTaskBuilder.Creator.stagedTaskForApp(appId, appVersion = version)
      .withAgentInfo(_.copy(host = host))
      .withHostPorts(portsList)

  private[this] val residentStagedTask =
    TestTaskBuilder.Creator.residentLaunchedTask(appId, Seq.empty[Task.LocalVolumeId]: _*)
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
    val eventsGenerator = InstanceChangedEventsGenerator

    // fixtures for healthChangedEvents testing
    private[this] val instance: Instance = TestTaskBuilder.Creator.runningTaskForApp(appId, startedAt = 100)
    private[this] val healthyInstanceState = InstanceState(InstanceStatus.Running, Timestamp.now(), Timestamp.now(), Some(true))
    private[this] val unhealthyInstanceState = InstanceState(InstanceStatus.Running, Timestamp.now(), Timestamp.now(), Some(false))
    private[this] val healthyInstance = instance.copy(state = healthyInstanceState)
    private[this] val unHealthyInstance = instance.copy(state = unhealthyInstanceState)

    private[this] def instanceUpdated(updatedInstance: Instance, lastState: InstanceState): InstanceChange = {
      InstanceUpdated(
        instance = updatedInstance,
        lastState = Some(lastState),
        events = Nil) // we don't care for InstanceChanged & MesosStatusUpdateEvent here, so event = Nil
    }
    def nowUnhealthy(): InstanceChange = instanceUpdated(updatedInstance = unHealthyInstance, lastState = healthyInstanceState)
    def nowHealthy(): InstanceChange = instanceUpdated(updatedInstance = healthyInstance, lastState = unhealthyInstanceState)
    def noHealthChange(): InstanceChange = instanceUpdated(updatedInstance = healthyInstance, lastState = healthyInstanceState)
    def healthChangedEvent(instance: Instance, healthy: Boolean): InstanceHealthChanged = InstanceHealthChanged(
      instance.instanceId,
      instance.runSpecVersion,
      instance.runSpecId,
      Some(healthy)
    )
  }
}
