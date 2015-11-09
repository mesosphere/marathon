package mesosphere.marathon.core.task.tracker.impl.steps

import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import mesosphere.marathon.test.Mockito
import org.apache.mesos.Protos.{ SlaveID, TaskState, TaskStatus }
import org.apache.mesos.SchedulerDriver
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }

import scala.collection.JavaConverters._
import scala.concurrent.Future

class UpdateTaskTrackerStepImplTest extends FunSuite with Matchers with ScalaFutures with Mockito with GivenWhenThen {
  test("name") {
    new Fixture().step.name should equal("updateTaskTracker")
  }

  test("processing task termination update (TASK_ERROR)") { processTerminalStateSuccessfully(TaskState.TASK_ERROR) }
  test("processing task termination update (TASK_FAILED)") { processTerminalStateSuccessfully(TaskState.TASK_FAILED) }
  test("processing task termination update (TASK_FINISHED)") { processTerminalStateSuccessfully(TaskState.TASK_FINISHED) }
  test("processing task termination update (TASK_KILLED)") { processTerminalStateSuccessfully(TaskState.TASK_KILLED) }
  test("processing task termination update (TASK_LOST)") { processTerminalStateSuccessfully(TaskState.TASK_LOST) }

  private[this] def processTerminalStateSuccessfully(terminalState: TaskState) {
    val f = new Fixture

    Given("a running task and a working taskTracker")
    val existingTask = runningMarathonTask
    val status = runningTaskStatus.toBuilder.setState(terminalState).build()
    f.taskTracker.terminated(appId, taskId.getValue) returns Future.successful(None)

    When("processUpdate is called")
    f.step.processUpdate(
      updateTimestamp,
      appId,
      existingTask,
      status
    ).futureValue

    Then("taskTracker.terminated is called")
    verify(f.taskTracker).terminated(appId, taskId.getValue)

    And("that's it")
    f.verifyNoMoreInteractions()
  }

  test("processing terminal update fails (TASK_ERROR)") { processTerminalStateUnsuccessfully(TaskState.TASK_ERROR) }
  test("processing terminal update fails (TASK_FAILED)") { processTerminalStateUnsuccessfully(TaskState.TASK_FAILED) }
  test("processing terminal update fails (TASK_FINISHED)") { processTerminalStateUnsuccessfully(TaskState.TASK_FINISHED) }
  test("processing terminal update fails (TASK_KILLED)") { processTerminalStateUnsuccessfully(TaskState.TASK_KILLED) }
  test("processing terminal update fails (TASK_LOST)") { processTerminalStateUnsuccessfully(TaskState.TASK_LOST) }

  private[this] def processTerminalStateUnsuccessfully(terminalState: TaskState) {
    val f = new Fixture

    Given("a running task and a working taskTracker")
    val existingTask = runningMarathonTask
    val status = runningTaskStatus.toBuilder.setState(terminalState).build()
    val failure: RuntimeException = new scala.RuntimeException("fail")
    f.taskTracker.terminated(appId, taskId.getValue) returns Future.failed(failure)

    When("processUpdate is called")
    val error = f.step.processUpdate(
      updateTimestamp,
      appId,
      existingTask,
      status
    ).failed.futureValue

    Then("taskTracker.terminated is called")
    verify(f.taskTracker).terminated(appId, taskId.getValue)

    And("the error is propagated")
    error.getMessage should equal("fail")

    And("that's it")
    f.verifyNoMoreInteractions()
  }

  test("processing running update for staged task") {
    val f = new Fixture

    Given("a running task and a working taskTracker")
    val existingTask = stagedMarathonTask
    val status = runningTaskStatus.toBuilder.setState(TaskState.TASK_RUNNING).build()
    f.taskTracker.running(appId, status) returns Future.successful(MarathonTask.getDefaultInstance)

    When("processUpdate is called")
    f.step.processUpdate(
      updateTimestamp,
      appId,
      existingTask,
      status
    ).futureValue

    Then("taskTracker.running is called")
    verify(f.taskTracker).running(appId, status)

    And("that's it")
    f.verifyNoMoreInteractions()
  }

  test("processing running update for running task") {
    val f = new Fixture

    Given("a running task and a working taskTracker")
    val existingTask = runningMarathonTask
    val status = runningTaskStatus.toBuilder.setState(TaskState.TASK_RUNNING).build()
    f.taskTracker.statusUpdate(appId, status) returns Future.successful(Some(MarathonTask.getDefaultInstance))

    When("processUpdate is called")
    f.step.processUpdate(
      updateTimestamp,
      appId,
      existingTask,
      status
    ).futureValue

    Then("taskTracker.statusUpdate is called")
    verify(f.taskTracker).statusUpdate(appId, status)

    And("that's it")
    f.verifyNoMoreInteractions()
  }

  test("processing running update for staged task fails") {
    val f = new Fixture

    Given("a running task and a broken taskTracker")
    val existingTask = stagedMarathonTask
    val status = runningTaskStatus.toBuilder.setState(TaskState.TASK_RUNNING).build()
    f.taskTracker.running(appId, status) returns Future.failed(new RuntimeException("I'm broken"))

    When("processUpdate is called")
    val eventualFailure = f.step.processUpdate(
      updateTimestamp,
      appId,
      existingTask,
      status
    ).failed.futureValue

    Then("taskTracker.running is called")
    verify(f.taskTracker).running(appId, status)

    And("the failure is propagated")
    eventualFailure.getMessage should equal("I'm broken")

    And("that's it")
    f.verifyNoMoreInteractions()
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

  private[this] val runningMarathonTask = stagedMarathonTask.toBuilder.setStartedAt(2).build()

  class Fixture {
    lazy val taskTracker = mock[TaskTracker]
    lazy val driver = mock[SchedulerDriver]
    lazy val driverOpt = Some(driver)
    lazy val driverHolder = {
      val ret = new MarathonSchedulerDriverHolder
      ret.driver = driverOpt
      ret
    }

    lazy val step = new UpdateTaskTrackerStepImpl(taskTracker)

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskTracker)
      noMoreInteractions(driver)
    }
  }
}
