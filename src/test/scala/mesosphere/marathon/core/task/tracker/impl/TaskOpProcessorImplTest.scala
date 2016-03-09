package mesosphere.marathon.core.task.tracker.impl

import akka.actor.Status
import akka.testkit.TestProbe
import mesosphere.marathon.core.task.{ Task, TaskStateChange, TaskStateOp }
import mesosphere.marathon.state.{ PathId, TaskRepository, Timestamp }
import mesosphere.marathon.test.{ CaptureLogEvents, MarathonActorSupport, Mockito }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

class TaskOpProcessorImplTest
    extends MarathonActorSupport with MarathonSpec with Mockito with GivenWhenThen with ScalaFutures with Matchers {

  import scala.concurrent.ExecutionContext.Implicits.global

  // ignored by the TaskOpProcessorImpl
  val deadline = Timestamp.zero

  test("process noop") {
    val f = new Fixture
    val appId = PathId("/app")

    When("the processor processes a noop")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, Task.Id("task1"), TaskOpProcessor.Action.Noop)
    )

    Then("it replies with unit immediately")
    result.futureValue should be(())

    And("the initiator gets its ack")
    expectMsg(())

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process fail") {
    val f = new Fixture
    val appId = PathId("/app")

    When("the processor processes a fail")
    val cause: RuntimeException = new scala.RuntimeException("fail")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, Task.Id("task1"), TaskOpProcessor.Action.Fail(cause))
    )

    Then("it replies with unit immediately")
    result.futureValue should be(())

    And("the initiator gets the failure")
    expectMsg(Status.Failure(cause))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with success") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val taskState = MarathonTestHelper.mininimalTask(appId)
    val task = taskState.marathonTask
    f.taskRepository.store(task) returns Future.successful(task)

    When("the processor processes an update")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, taskState.taskId, TaskOpProcessor.Action.Update(taskState))
    )

    Then("it replies with unit immediately")
    result.futureValue should be(()) // first wait for the call to complete

    And("it calls store")
    verify(f.taskRepository).store(task)

    And("the taskTracker gets the update")
    f.taskTrackerProbe.expectMsg(TaskTrackerActor.TaskUpdated(taskState, TaskTrackerActor.Ack(testActor, ())))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with failing taskRepository.store but successful load of existing task") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository and existing task")
    val taskState = MarathonTestHelper.stagedTaskForApp(appId)
    val task = taskState.marathonTask
    f.taskRepository.store(task) returns Future.failed(new RuntimeException("fail"))
    f.taskRepository.task(task.getId) returns Future.successful(Some(task))

    When("the processor processes an update")

    var result: Try[Unit] = Failure(new RuntimeException("test executing failed"))
    val logs = CaptureLogEvents.forBlock {
      result = Try(f.processor.process(
        TaskOpProcessor.Operation(deadline, testActor, taskState.taskId, TaskOpProcessor.Action.Update(taskState))
      ).futureValue) // we need to complete the future here to get all the logs
    }

    Then("it calls store")
    verify(f.taskRepository).store(task)

    And("logs a warning after detecting the error")
    logs.head.getLevel should be(ch.qos.logback.classic.Level.WARN)
    logs.head.getMessage should include(s"[${task.getId}]")

    And("loads the task")
    verify(f.taskRepository).task(task.getId)

    And("it replies with unit immediately because the task is as expected")
    result should be(Success(()))

    And("the taskTracker gets the update")
    f.taskTrackerProbe.expectMsg(TaskTrackerActor.TaskUpdated(taskState, TaskTrackerActor.Ack(testActor, ())))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with failing taskRepository.store but successful load of non-existing task") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository and no task")
    val taskState = MarathonTestHelper.mininimalTask(appId)
    val task = taskState.marathonTask
    val storeFail: RuntimeException = new scala.RuntimeException("fail")
    f.taskRepository.store(task) returns Future.failed(storeFail)
    f.taskRepository.task(task.getId) returns Future.successful(None)

    When("the processor processes an update")

    var result: Try[Unit] = Failure(new RuntimeException("test executing failed"))
    val logs = CaptureLogEvents.forBlock {
      result = Try(f.processor.process(
        TaskOpProcessor.Operation(deadline, testActor, taskState.taskId, TaskOpProcessor.Action.Update(taskState))
      ).futureValue) // we need to complete the future here to get all the logs
    }

    Then("it calls store")
    verify(f.taskRepository).store(task)

    And("logs a warning after detecting the error")
    logs.head.getLevel should be(ch.qos.logback.classic.Level.WARN)
    logs.head.getMessage should include(s"[${task.getId}]")

    And("loads the task")
    verify(f.taskRepository).task(task.getId)

    And("it replies with unit immediately because the task is as expected")
    result should be(Success(()))

    And("the taskTracker gets a task removed and the ack contains the original failure")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.TaskRemoved(taskState.taskId, TaskTrackerActor.Ack(testActor, Status.Failure(storeFail)))
    )

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with failing taskRepository.store and load also fails") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository and existing task")
    val taskState = MarathonTestHelper.mininimalTask(appId)
    val task = taskState.marathonTask
    val storeFailed: RuntimeException = new scala.RuntimeException("store failed")
    f.taskRepository.store(task) returns Future.failed(storeFailed)
    f.taskRepository.task(task.getId) returns Future.failed(new RuntimeException("task failed"))

    When("the processor processes an update")
    var result: Try[Unit] = Failure(new RuntimeException("test executing failed"))
    val logs = CaptureLogEvents.forBlock {
      result = Try(f.processor.process(
        TaskOpProcessor.Operation(deadline, testActor, taskState.taskId, TaskOpProcessor.Action.Update(taskState))
      ).futureValue) // we need to complete the future here to get all the logs
    }

    Then("it calls store")
    verify(f.taskRepository).store(task)

    And("loads the task")
    verify(f.taskRepository).task(task.getId)

    And("logs a two warnings, for store and for task")
    logs.head.getLevel should be(ch.qos.logback.classic.Level.WARN)
    logs.head.getMessage should include(s"[${task.getId}]")
    logs(1).getLevel should be(ch.qos.logback.classic.Level.WARN)
    logs(1).getMessage should include(s"[${task.getId}]")

    And("it replies with the original error")
    result.failed.get.getCause.getMessage should be(storeFailed.getMessage)

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process expunge with success") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val taskId = "task1"
    f.taskRepository.expunge(taskId) returns Future.successful(Iterable(true))

    When("the processor processes an update")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, Task.Id(taskId), TaskOpProcessor.Action.Expunge)
    )

    Then("it replies with unit immediately")
    result.futureValue should be(()) // first we make sure that the call completes

    And("it calls expunge")
    verify(f.taskRepository).expunge(taskId)

    And("the taskTracker gets the update")
    f.taskTrackerProbe.expectMsg(TaskTrackerActor.TaskRemoved(Task.Id(taskId), TaskTrackerActor.Ack(testActor, ())))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process expunge, expunge fails but task reload confirms that task is gone") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val taskId = Task.Id.forApp(appId)
    f.taskRepository.expunge(taskId.idString) returns Future.failed(new RuntimeException("expunge fails"))
    f.taskRepository.task(taskId.idString) returns Future.successful(None)

    When("the processor processes an update")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, taskId, TaskOpProcessor.Action.Expunge)
    )

    Then("it replies with unit immediately")
    result.futureValue should be(()) // first we make sure that the call completes

    And("it calls expunge")
    verify(f.taskRepository).expunge(taskId.idString)

    And("it reloads the task")
    verify(f.taskRepository).task(taskId.idString)

    And("the taskTracker gets the update")
    f.taskTrackerProbe.expectMsg(TaskTrackerActor.TaskRemoved(taskId, TaskTrackerActor.Ack(testActor, ())))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process expunge, expunge fails but and task reload suggests that task is still there") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val taskState = MarathonTestHelper.mininimalTask(appId)
    val task = taskState.marathonTask
    val taskId = task.getId
    val expungeFails: RuntimeException = new scala.RuntimeException("expunge fails")
    f.taskRepository.expunge(taskId) returns Future.failed(expungeFails)
    f.taskRepository.task(taskId) returns Future.successful(Some(task))

    When("the processor processes an update")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, taskState.taskId, TaskOpProcessor.Action.Expunge)
    )

    Then("it replies with unit immediately")
    result.futureValue should be(()) // first we make sure that the call completes

    And("it calls expunge")
    verify(f.taskRepository).expunge(taskId)

    And("it reloads the task")
    verify(f.taskRepository).task(taskId)

    And("the taskTracker gets the update and the ack contains the expunge failure")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.TaskUpdated(taskState, TaskTrackerActor.Ack(testActor, Status.Failure(expungeFails)))
    )

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process statusUpdate with success") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a statusUpdateResolver and an update")
    val update: TaskStatus = TaskStatus.getDefaultInstance
    val taskId = "task1"
    f.statusUpdateResolver.resolve(Task.Id(taskId), update) returns
      Future.successful(TaskOpProcessor.Action.Noop)

    When("the processor processes an update")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, Task.Id(taskId), TaskOpProcessor.Action.UpdateStatus(update))
    )

    Then("it replies with unit immediately")
    result.futureValue should be(()) // first we make sure that the call completes

    And("it calls expunge")
    verify(f.statusUpdateResolver).resolve(Task.Id(taskId), update)

    And("the initiator gets its ack")
    expectMsg(())

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process terminated statusUpdate for resident tasks") {
    import MarathonTestHelper._

    Given("a statusUpdateResolver and an update")
    val now = Timestamp.now()
    val f = new Fixture
    val appId = PathId("/app")
    val unlaunched: Task = minimalReservedTask(appId, newReservation)
    val launched: Task = f.toLaunched(unlaunched, taskLaunchedOp)
    val marathonTask = unlaunched.marathonTask
    val taskId = launched.taskId.mesosTaskId.getValue
    val killed: TaskStatus = MarathonTestHelper.statusForState(taskId, TaskState.TASK_KILLED)
    f.taskRepository.store(marathonTask) returns Future.successful(marathonTask)
    f.statusUpdateResolver.resolve(Task.Id(taskId), killed) returns Future.successful(TaskOpProcessor.Action.Update(unlaunched))

    When("the processor processes an update")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, Task.Id(taskId), TaskOpProcessor.Action.Update(unlaunched))
    )

    Then("it replies with unit immediately")
    result.futureValue should be(())

    And("The task is stored")
    verify(f.taskRepository).store(marathonTask)

    And("the taskTracker gets the update")
    f.taskTrackerProbe.expectMsg(TaskTrackerActor.TaskUpdated(unlaunched, TaskTrackerActor.Ack(testActor, ())))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  class Fixture {
    lazy val config = MarathonTestHelper.defaultConfig()
    lazy val taskTrackerProbe = TestProbe()
    lazy val taskRepository = mock[TaskRepository]
    lazy val statusUpdateResolver = mock[TaskOpProcessorImpl.StatusUpdateActionResolver]
    lazy val processor = new TaskOpProcessorImpl(taskTrackerProbe.ref, taskRepository, statusUpdateResolver)

    def verifyNoMoreInteractions(): Unit = {
      taskTrackerProbe.expectNoMsg(0.seconds)
      noMoreInteractions(taskRepository)
      noMoreInteractions(statusUpdateResolver)
    }

    def toLaunched(task: Task, taskStateOp: TaskStateOp.Launch): Task =
      task.update(taskStateOp) match {
        case TaskStateChange.Update(launchedTask) => launchedTask
        case _                                    => throw new scala.RuntimeException("taskStateOp did not result in a launched task")
      }
  }
}
