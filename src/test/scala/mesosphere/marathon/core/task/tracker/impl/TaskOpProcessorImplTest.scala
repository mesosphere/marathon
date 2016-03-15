package mesosphere.marathon.core.task.tracker.impl

import akka.actor.Status
import akka.testkit.TestProbe
import mesosphere.marathon.core.task.bus.{ MarathonTaskStatus, MarathonTaskStatusTestHelper }
import mesosphere.marathon.core.task.{ Task, TaskStateChange, TaskStateOp }
import mesosphere.marathon.state.{ PathId, TaskRepository, Timestamp }
import mesosphere.marathon.test.{ CaptureLogEvents, MarathonActorSupport, Mockito }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
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

  test("process update with success") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val taskState = MarathonTestHelper.mininimalTask(appId)
    val task = taskState.marathonTask
    val stateOp = f.stateOpUpdate(taskState, MarathonTaskStatusTestHelper.runningHealthy)
    val expectedChange = TaskStateChange.Update(taskState)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.task(taskState.taskId.idString) returns Future.successful(Some(task))
    f.taskRepository.store(task) returns Future.successful(task)

    When("the processor processes an update")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, taskState.taskId, stateOp)
    )

    Then("it replies with unit immediately")
    result.futureValue should be(()) // first wait for the call to complete

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("it calls store")
    verify(f.taskRepository).store(task)

    And("the taskTracker gets the update")
    f.taskTrackerProbe.expectMsg(TaskTrackerActor.TaskUpdated(expectedChange, TaskTrackerActor.Ack(testActor, expectedChange)))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with failing taskRepository.store but successful load of existing task") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository and existing task")
    val taskState = MarathonTestHelper.stagedTaskForApp(appId)
    val task = taskState.marathonTask
    val stateOp = f.stateOpUpdate(taskState, MarathonTaskStatusTestHelper.running)
    val expectedChange = TaskStateChange.Update(taskState)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.store(task) returns Future.failed(new RuntimeException("fail"))
    f.taskRepository.task(task.getId) returns Future.successful(Some(task))

    When("the processor processes an update")

    var result: Try[Unit] = Failure(new RuntimeException("test executing failed"))
    val logs = CaptureLogEvents.forBlock {
      result = Try(f.processor.process(
        TaskOpProcessor.Operation(deadline, testActor, taskState.taskId, stateOp)
      ).futureValue) // we need to complete the future here to get all the logs
    }

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

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
    f.taskTrackerProbe.expectMsg(TaskTrackerActor.TaskUpdated(expectedChange, TaskTrackerActor.Ack(testActor, ())))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with failing taskRepository.store but successful load of non-existing task") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository and no task")
    val taskState = MarathonTestHelper.mininimalTask(appId)
    val task = taskState.marathonTask
    val stateOp = f.stateOpUpdate(taskState, MarathonTaskStatusTestHelper.running)
    val expectedChange = TaskStateChange.Update(taskState)
    val storeFail: RuntimeException = new scala.RuntimeException("fail")
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.store(task) returns Future.failed(storeFail)
    f.taskRepository.task(task.getId) returns Future.successful(None)

    When("the processor processes an update")

    var result: Try[Unit] = Failure(new RuntimeException("test executing failed"))
    val logs = CaptureLogEvents.forBlock {
      result = Try(f.processor.process(
        TaskOpProcessor.Operation(deadline, testActor, taskState.taskId, stateOp)
      ).futureValue) // we need to complete the future here to get all the logs
    }

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

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
      TaskTrackerActor.TaskRemoved(TaskStateChange.Expunge(taskState.taskId), TaskTrackerActor.Ack(testActor, Status.Failure(storeFail)))
    )

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with failing taskRepository.store and load also fails") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository and existing task")
    val taskState = MarathonTestHelper.mininimalTask(appId)
    val task = TaskSerializer.toProto(taskState)
    val storeFailed: RuntimeException = new scala.RuntimeException("store failed")
    val stateOp = f.stateOpUpdate(taskState, MarathonTaskStatusTestHelper.running)
    val expectedChange = TaskStateChange.Update(taskState)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.store(task) returns Future.failed(storeFailed)
    f.taskRepository.task(task.getId) returns Future.failed(new RuntimeException("task failed"))

    When("the processor processes an update")
    var result: Try[Unit] = Failure(new RuntimeException("test executing failed"))
    val logs = CaptureLogEvents.forBlock {
      result = Try(f.processor.process(
        TaskOpProcessor.Operation(deadline, testActor, taskState.taskId, stateOp)
      ).futureValue) // we need to complete the future here to get all the logs
    }

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    Then("it calls store")
    verify(f.taskRepository).store(task)

    And("loads the task")
    verify(f.taskRepository).task(task.getId)

    And("it replies with the original error")
    result.isFailure shouldBe true
    result.failed.get.getCause.getMessage should be(storeFailed.getMessage)

    And("logs a two warnings, for store and for task")
    logs.head.getLevel should be(ch.qos.logback.classic.Level.WARN)
    logs.head.getMessage should include(s"[${task.getId}]")
    logs(1).getLevel should be(ch.qos.logback.classic.Level.WARN)
    logs(1).getMessage should include(s"[${task.getId}]")

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process expunge with success") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val taskState = MarathonTestHelper.mininimalTask(appId)
    val taskId = taskState.taskId
    val taskIdString = taskId.idString
    val stateOp = f.stateOpExpunge(taskState)
    val expectedChange = TaskStateChange.Expunge(taskState.taskId)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.expunge(taskIdString) returns Future.successful(Iterable(true))

    When("the processor processes an update")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, taskId, TaskStateOp.ForceExpunge(taskId))
    ).futureValue // first we make sure that the call completes

    Then("it replies with unit immediately")
    result should be(())

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("it calls expunge")
    verify(f.taskRepository).expunge(taskIdString)

    And("the taskTracker gets the update")
    f.taskTrackerProbe.expectMsg(TaskTrackerActor.TaskRemoved(TaskStateChange.Expunge(taskId), TaskTrackerActor.Ack(testActor, TaskStateChange.Expunge(taskId))))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process expunge, expunge fails but task reload confirms that task is gone") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val taskState = MarathonTestHelper.mininimalTask(appId)
    val taskId = taskState.taskId
    val stateOp = f.stateOpExpunge(taskState)
    val expectedChange = TaskStateChange.Expunge(taskState.taskId)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.expunge(taskId.idString) returns Future.failed(new RuntimeException("expunge fails"))
    f.taskRepository.task(taskId.idString) returns Future.successful(None)

    When("the processor processes an update")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, taskId, TaskStateOp.ForceExpunge(taskId))
    ).futureValue

    Then("it replies with unit immediately")
    result should be(()) // first we make sure that the call completes

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("it calls expunge")
    verify(f.taskRepository).expunge(taskId.idString)

    And("it reloads the task")
    verify(f.taskRepository).task(taskId.idString)

    And("the taskTracker gets the update")
    f.taskTrackerProbe.expectMsg(TaskTrackerActor.TaskRemoved(TaskStateChange.Expunge(taskId), TaskTrackerActor.Ack(testActor, ())))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process expunge, expunge fails and task reload suggests that task is still there") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val taskState = MarathonTestHelper.mininimalTask(appId)
    val task = taskState.marathonTask
    val taskId = task.getId
    val expungeFails: RuntimeException = new scala.RuntimeException("expunge fails")
    val stateOp = f.stateOpExpunge(taskState)
    val expectedChange = TaskStateChange.Expunge(taskState.taskId)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.expunge(taskId) returns Future.failed(expungeFails)
    f.taskRepository.task(taskId) returns Future.successful(Some(task))

    When("the processor processes an update")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, taskState.taskId, TaskStateOp.ForceExpunge(taskState.taskId))
    )

    Then("it replies with unit immediately")
    result.futureValue should be(()) // first we make sure that the call completes

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("it calls expunge")
    verify(f.taskRepository).expunge(taskId)

    And("it reloads the task")
    verify(f.taskRepository).task(taskId)

    And("the taskTracker gets the update and the ack contains the expunge failure")
    f.taskTrackerProbe.expectMsg(
      TaskTrackerActor.TaskUpdated(TaskStateChange.Update(taskState), TaskTrackerActor.Ack(testActor, Status.Failure(expungeFails)))
    )

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process statusUpdate with NoChange") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a statusUpdateResolver and an update")
    val taskState = MarathonTestHelper.mininimalTask(appId)
    val stateOp = f.stateOpUpdate(taskState, MarathonTaskStatusTestHelper.running)
    val expectedChange = TaskStateChange.NoChange(taskState.taskId)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)

    When("the processor processes an update")
    val result = f.processor.process(
      TaskOpProcessor.Operation(deadline, testActor, taskState.taskId, stateOp)
    )

    Then("it replies with unit immediately")
    result.futureValue should be(()) // first we make sure that the call completes

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("the initiator gets its ack")
    expectMsg(TaskStateChange.NoChange(taskState.taskId))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  class Fixture {
    lazy val config = MarathonTestHelper.defaultConfig()
    lazy val taskTrackerProbe = TestProbe()
    lazy val taskRepository = mock[TaskRepository]
    lazy val stateOpResolver = mock[TaskOpProcessorImpl.TaskStateOpResolver]
    lazy val processor = new TaskOpProcessorImpl(taskTrackerProbe.ref, taskRepository, stateOpResolver)
    lazy val now = Timestamp(0)

    def stateOpCreate(task: Task) = TaskStateOp.Create(task)
    def stateOpUpdate(task: Task, status: MarathonTaskStatus) = TaskStateOp.MesosUpdate(task.taskId, status, now)
    def stateOpExpunge(task: Task) = TaskStateOp.ForceExpunge(task.taskId)
    def stateOpLaunchOnReservation(task: Task, status: Task.Status) = TaskStateOp.LaunchOnReservation(task.taskId, now, status, Task.NoNetworking)
    def stateOpReservationTimeout(task: Task) = TaskStateOp.ReservationTimeout(task.taskId)
    def stateOpExpunge(task: Task.Reserved) = TaskStateOp.Reserve(task)

    def verifyNoMoreInteractions(): Unit = {
      taskTrackerProbe.expectNoMsg(0.seconds)
      noMoreInteractions(taskRepository)
      noMoreInteractions(stateOpResolver)
    }

    def toLaunched(task: Task, taskStateOp: TaskStateOp.LaunchOnReservation): Task =
      task.update(taskStateOp) match {
        case TaskStateChange.Update(launchedTask) => launchedTask
        case _                                    => throw new scala.RuntimeException("taskStateOp did not result in a launched task")
      }
  }
}
