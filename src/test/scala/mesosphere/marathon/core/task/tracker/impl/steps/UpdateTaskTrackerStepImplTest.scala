package mesosphere.marathon.core.task.tracker.impl.steps

import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskUpdater }
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

  test("processing update succeeds") {
    val f = new Fixture

    Given("a running task and a working taskTracker")
    val existingTask = runningMarathonTask
    val status = runningTaskStatus.toBuilder.setState(TaskState.TASK_RUNNING).build()
    f.taskUpdater.statusUpdate(appId, status).asInstanceOf[Future[Unit]] returns Future.successful(())

    When("processUpdate is called")
    f.step.processUpdate(
      updateTimestamp,
      appId,
      existingTask,
      status
    ).futureValue

    Then("taskTracker.statusUpdate is called")
    verify(f.taskUpdater).statusUpdate(appId, status)

    And("that's it")
    f.verifyNoMoreInteractions()
  }

  test("processing update fails") {
    val f = new Fixture

    Given("a running task and a broken taskTracker")
    val existingTask = stagedMarathonTask
    val status = runningTaskStatus.toBuilder.setState(TaskState.TASK_RUNNING).build()
    f.taskUpdater.statusUpdate(appId, status).asInstanceOf[Future[Unit]] returns
      Future.failed(new RuntimeException("I'm broken"))

    When("processUpdate is called")
    val eventualFailure = f.step.processUpdate(
      updateTimestamp,
      appId,
      existingTask,
      status
    ).failed.futureValue

    Then("taskTracker.statusUpdate is called")
    verify(f.taskUpdater).statusUpdate(appId, status)

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
    lazy val taskUpdater = mock[TaskUpdater]
    lazy val driver = mock[SchedulerDriver]
    lazy val driverOpt = Some(driver)
    lazy val driverHolder = {
      val ret = new MarathonSchedulerDriverHolder
      ret.driver = driverOpt
      ret
    }

    lazy val step = new UpdateTaskTrackerStepImpl(taskUpdater)

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskUpdater)
      noMoreInteractions(driver)
    }
  }
}
