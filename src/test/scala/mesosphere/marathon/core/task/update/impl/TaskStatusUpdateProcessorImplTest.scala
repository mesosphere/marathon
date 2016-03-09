package mesosphere.marathon.core.task.update.impl

import akka.actor.ActorSystem
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.bus.TaskStatusUpdateTestHelper
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, TaskTracker }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonSpec, MarathonTestHelper }
import org.apache.mesos.SchedulerDriver
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.Future

class TaskStatusUpdateProcessorImplTest
    extends MarathonSpec with Mockito with ScalaFutures with GivenWhenThen with Matchers {
  test("process update for unknown task that's not lost will result in a kill and ack") {
    fOpt = Some(new Fixture)
    val origUpdate = TaskStatusUpdateTestHelper.finished() // everything != lost is handled in the same way
    val status = origUpdate.status
    val update = origUpdate
    val taskId = update.wrapped.stateOp.taskId

    Given("an unknown task")
    import scala.concurrent.ExecutionContext.Implicits.global
    f.taskTracker.task(taskId)(global) returns Future.successful(None)

    When("we process the updated")
    f.updateProcessor.publish(status).futureValue

    Then("we expect that the appropriate taskTracker methods have been called")
    verify(f.taskTracker).task(taskId)(global)

    And("the task kill gets initiated")
    verify(f.schedulerDriver).killTask(status.getTaskId)
    And("the update has been acknowledged")
    verify(f.schedulerDriver).acknowledgeStatusUpdate(status)

    And("that's it")
    f.verifyNoMoreInteractions()
  }

  test("process update for known task without launchedTask that's not lost will result in a kill and ack") {
    fOpt = Some(new Fixture)
    val origUpdate = TaskStatusUpdateTestHelper.finished() // everything != lost is handled in the same way
    val status = origUpdate.status
    val update = origUpdate
    val taskId = update.wrapped.stateOp.taskId

    Given("an unknown task")
    import scala.concurrent.ExecutionContext.Implicits.global
    f.taskTracker.task(taskId)(global) returns Future.successful(
      Some(MarathonTestHelper.minimalReservedTask(
        taskId.appId, Task.Reservation(Iterable.empty, MarathonTestHelper.taskReservationStateNew)))
    )

    When("we process the updated")
    f.updateProcessor.publish(status).futureValue

    Then("we expect that the appropriate taskTracker methods have been called")
    verify(f.taskTracker).task(taskId)(global)

    And("the task kill gets initiated")
    verify(f.schedulerDriver).killTask(status.getTaskId)
    And("the update has been acknowledged")
    verify(f.schedulerDriver).acknowledgeStatusUpdate(status)

    And("that's it")
    f.verifyNoMoreInteractions()
  }

  test("update for unknown task (TASK_LOST) will get only acknowledged") {
    fOpt = Some(new Fixture)

    val origUpdate = TaskStatusUpdateTestHelper.lost()
    val status = origUpdate.status
    val update = origUpdate
    val taskId = update.wrapped.stateOp.taskId

    Given("an unknown task")
    import scala.concurrent.ExecutionContext.Implicits.global
    f.taskTracker.task(taskId)(global) returns Future.successful(None)

    When("we process the updated")
    f.updateProcessor.publish(status).futureValue

    Then("we expect that the appropriate taskTracker methods have been called")
    verify(f.taskTracker).task(taskId)(global)

    And("the update has been acknowledged")
    verify(f.schedulerDriver).acknowledgeStatusUpdate(status)

    And("that's it")
    f.verifyNoMoreInteractions()
  }

  var fOpt: Option[Fixture] = None
  def f = fOpt.get

  lazy val appId = PathId("/app")
  lazy val app = AppDefinition(appId)
  lazy val version = Timestamp.now()
  lazy val task = MarathonTestHelper.makeOneCPUTask(Task.Id.forApp(appId).mesosTaskId.getValue).build()
  lazy val taskState = MarathonTestHelper.stagedTask(task.getTaskId.getValue, appVersion = version)
  lazy val marathonTask = taskState.marathonTask

  after {
    fOpt.foreach(_.shutdown())
  }

  class Fixture {
    implicit lazy val actorSystem: ActorSystem = ActorSystem()
    lazy val clock: ConstantClock = ConstantClock()

    lazy val taskTracker: TaskTracker = mock[TaskTracker]
    lazy val stateOpProcessor: TaskStateOpProcessor = mock[TaskStateOpProcessor]
    lazy val schedulerDriver: SchedulerDriver = mock[SchedulerDriver]
    lazy val marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder = {
      val holder = new MarathonSchedulerDriverHolder
      holder.driver = Some(schedulerDriver)
      holder
    }

    lazy val updateProcessor = new TaskStatusUpdateProcessorImpl(
      new Metrics(new MetricRegistry),
      clock,
      taskTracker,
      stateOpProcessor,
      marathonSchedulerDriverHolder
    )

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(taskTracker)
      noMoreInteractions(schedulerDriver)

      shutdown()
    }

    def shutdown(): Unit = {
      actorSystem.shutdown()
      actorSystem.awaitTermination()
      fOpt = None
    }
  }
}
