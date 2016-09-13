package mesosphere.marathon.core.task.tracker.impl

import akka.Done
import akka.actor.{ ActorRef, Status }
import akka.event.EventStream
import akka.testkit.TestProbe
import ch.qos.logback.classic.Level
import com.codahale.metrics.MetricRegistry
import com.google.inject.Provider
import mesosphere.marathon.core.CoreGuiceModule
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.bus.{ MesosTaskStatusTestHelper, TaskStatusEmitter }
import mesosphere.marathon.core.task.tracker.TaskUpdater
import mesosphere.marathon.core.task.update.impl.steps.{ NotifyHealthCheckManagerStepImpl, NotifyLaunchQueueStepImpl, NotifyRateLimiterStepImpl, PostToEventStreamStepImpl, ScaleAppUpdateStepImpl, TaskStatusEmitterPublishStepImpl }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.storage.repository.{ AppRepository, ReadOnlyAppRepository, TaskRepository }
import mesosphere.marathon.test.{ CaptureLogEvents, MarathonActorSupport, Mockito }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import org.apache.mesos
import org.apache.mesos.SchedulerDriver
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

class InstanceOpProcessorImplTest
    extends MarathonActorSupport with MarathonSpec with Mockito with GivenWhenThen with ScalaFutures with Matchers {

  import scala.concurrent.ExecutionContext.Implicits.global

  // ignored by the TaskOpProcessorImpl
  val deadline = Timestamp.zero

  test("process update with success") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val task = MarathonTestHelper.mininimalTask(appId)
    val taskProto = TaskSerializer.toProto(task)
    val stateOp = f.stateOpUpdate(task, MesosTaskStatusTestHelper.runningHealthy)
    val mesosStatus = stateOp.mesosStatus
    val expectedChange = InstanceUpdateEffect.Update(task, Some(task))
    val taskChanged = TaskChanged(stateOp, expectedChange)
    val ack = InstanceTrackerActor.Ack(f.opSender.ref, expectedChange)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.get(task.taskId) returns Future.successful(Some(task))
    f.taskRepository.store(task) returns Future.successful(Done)
    f.taskUpdater.statusUpdate(appId, mesosStatus).asInstanceOf[Future[Unit]] returns Future.successful(())

    When("the processor processes an update")
    val result = f.processor.process(
      InstanceOpProcessor.Operation(deadline, f.opSender.ref, task.taskId, stateOp)
    )

    And("the taskTracker replies immediately")
    f.taskTrackerProbe.expectMsg(InstanceTrackerActor.StateChanged(taskChanged, ack))
    f.taskTrackerProbe.reply(())

    And("the processor replies with unit accordingly")
    result.futureValue should be(()) // first wait for the call to complete

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("it calls store")
    verify(f.taskRepository).store(task)

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with failing taskRepository.store but successful load of existing task") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository and existing task")
    val task = MarathonTestHelper.stagedTaskForApp(appId)
    val taskProto = TaskSerializer.toProto(task)
    val stateOp = f.stateOpUpdate(task, MesosTaskStatusTestHelper.running)
    val expectedChange = InstanceUpdateEffect.Update(task, Some(task))
    val taskChanged = TaskChanged(stateOp, expectedChange)
    val ack = InstanceTrackerActor.Ack(f.opSender.ref, expectedChange)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.store(task) returns Future.failed(new RuntimeException("fail"))
    f.taskRepository.get(task.taskId) returns Future.successful(Some(task))

    When("the processor processes an update")
    var result: Try[Unit] = Failure(new RuntimeException("test executing failed"))
    val logs = CaptureLogEvents.forBlock {
      val resultF = f.processor.process(
        InstanceOpProcessor.Operation(deadline, f.opSender.ref, task.taskId, stateOp)
      )
      f.taskTrackerProbe.expectMsg(InstanceTrackerActor.StateChanged(taskChanged, ack))
      f.taskTrackerProbe.reply(())
      result = Try(resultF.futureValue) // we need to complete the future here to get all the logs
    }

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    Then("it calls store")
    verify(f.taskRepository).store(task)

    And("logs a warning after detecting the error")
    logs.filter(l => l.getLevel == Level.WARN && l.getMessage.contains(s"[${taskProto.getId}]")) should have size 1

    And("loads the task")
    verify(f.taskRepository).get(task.taskId)

    And("it replies with unit immediately because the task is as expected")
    result should be(Success(()))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with failing taskRepository.store and successful load of non-existing task") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository and no task")
    val task = MarathonTestHelper.mininimalTask(appId)
    val taskProto = TaskSerializer.toProto(task)
    val stateOp = f.stateOpUpdate(task, MesosTaskStatusTestHelper.running)
    val resolvedStateChange = InstanceUpdateEffect.Update(task, Some(task))
    val storeException: RuntimeException = new scala.RuntimeException("fail")
    val expectedTaskChanged = TaskChanged(stateOp, InstanceUpdateEffect.Failure(storeException))
    val ack = InstanceTrackerActor.Ack(f.opSender.ref, expectedTaskChanged.stateChange)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(resolvedStateChange)
    f.taskRepository.store(task) returns Future.failed(storeException)
    f.taskRepository.get(task.taskId) returns Future.successful(None)

    When("the processor processes an update")

    var result: Try[Unit] = Failure(new RuntimeException("test executing failed"))
    val logs = CaptureLogEvents.forBlock {
      val resultF = f.processor.process(
        InstanceOpProcessor.Operation(deadline, f.opSender.ref, task.taskId, stateOp)
      )
      f.taskTrackerProbe.expectMsg(InstanceTrackerActor.StateChanged(expectedTaskChanged, ack))
      f.taskTrackerProbe.reply(())
      result = Try(resultF.futureValue) // we need to complete the future here to get all the logs
    }

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    Then("it calls store")
    verify(f.taskRepository).store(task)

    And("logs a warning after detecting the error")
    logs.filter(l => l.getLevel == Level.WARN && l.getMessage.contains(s"[${taskProto.getId}]")) should have size 1

    And("loads the task")
    verify(f.taskRepository).get(task.taskId)

    And("it replies with unit immediately because the task is as expected")
    result should be(Success(()))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with failing taskRepository.store and load also fails") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository and existing task")
    val task = MarathonTestHelper.mininimalTask(appId)
    val taskProto = TaskSerializer.toProto(task)
    val storeFailed: RuntimeException = new scala.RuntimeException("store failed")
    val stateOp = f.stateOpUpdate(task, MesosTaskStatusTestHelper.running)
    val expectedChange = InstanceUpdateEffect.Update(task, Some(task))
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.store(task) returns Future.failed(storeFailed)
    f.taskRepository.get(task.taskId) returns Future.failed(new RuntimeException("task failed"))

    When("the processor processes an update")
    var result: Try[Unit] = Failure(new RuntimeException("test executing failed"))
    val logs = CaptureLogEvents.forBlock {
      result = Try(f.processor.process(
        InstanceOpProcessor.Operation(deadline, f.opSender.ref, task.taskId, stateOp)
      ).futureValue) // we need to complete the future here to get all the logs
    }

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    Then("it calls store")
    verify(f.taskRepository).store(task)

    And("loads the task")
    verify(f.taskRepository).get(task.taskId)

    And("it replies with the original error")
    result.isFailure shouldBe true
    result.failed.get.getCause.getMessage should be(storeFailed.getMessage)

    And("logs a two warnings, for store and for task")
    logs.filter(l => l.getLevel == Level.WARN && l.getMessage.contains(s"[${taskProto.getId}]")) should have size 2

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process expunge with success") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val task = MarathonTestHelper.mininimalTask(appId)
    val taskId = task.taskId
    val taskIdString = taskId.idString
    val stateOp = f.stateOpExpunge(task)
    val expectedChange = InstanceUpdateEffect.Expunge(task)
    val taskChanged = TaskChanged(stateOp, expectedChange)
    val ack = InstanceTrackerActor.Ack(f.opSender.ref, expectedChange)

    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.delete(task.taskId) returns Future.successful(Done)

    When("the processor processes an update")
    val result = f.processor.process(
      InstanceOpProcessor.Operation(deadline, f.opSender.ref, taskId, InstanceUpdateOperation.ForceExpunge(taskId))
    )
    f.taskTrackerProbe.expectMsg(InstanceTrackerActor.StateChanged(taskChanged, ack))
    f.taskTrackerProbe.reply(())

    Then("it replies with unit immediately")
    result.futureValue should be(())

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("it calls expunge")
    verify(f.taskRepository).delete(task.taskId)

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process expunge, expunge fails but task reload confirms that task is gone") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val task = MarathonTestHelper.mininimalTask(appId)
    val taskId = task.taskId
    val stateOp = f.stateOpExpunge(task)
    val expectedChange = InstanceUpdateEffect.Expunge(task)
    val taskChanged = TaskChanged(stateOp, expectedChange)
    val ack = InstanceTrackerActor.Ack(f.opSender.ref, expectedChange)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.delete(taskId) returns Future.failed(new RuntimeException("expunge fails"))
    f.taskRepository.get(taskId) returns Future.successful(None)

    When("the processor processes an update")
    val result = f.processor.process(
      InstanceOpProcessor.Operation(deadline, f.opSender.ref, taskId, InstanceUpdateOperation.ForceExpunge(taskId))
    )
    f.taskTrackerProbe.expectMsg(InstanceTrackerActor.StateChanged(taskChanged, ack))
    f.taskTrackerProbe.reply(())

    Then("it replies with unit immediately")
    result.futureValue should be(())

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("it calls expunge")
    verify(f.taskRepository).delete(taskId)

    And("it reloads the task")
    verify(f.taskRepository).get(taskId)

    And("the taskTracker gets the update")

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process expunge, expunge fails and task reload suggests that task is still there") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val task = MarathonTestHelper.mininimalTask(appId)
    val taskProto = TaskSerializer.toProto(task)
    val taskId = taskProto.getId
    val expungeException: RuntimeException = new scala.RuntimeException("expunge fails")
    val stateOp = f.stateOpExpunge(task)
    val resolvedStateChange = InstanceUpdateEffect.Expunge(task)
    val expectedTaskChanged = TaskChanged(stateOp, InstanceUpdateEffect.Failure(expungeException))
    val ack = InstanceTrackerActor.Ack(f.opSender.ref, expectedTaskChanged.stateChange)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(resolvedStateChange)
    f.taskRepository.delete(task.taskId) returns Future.failed(expungeException)
    f.taskRepository.get(task.taskId) returns Future.successful(Some(task))

    When("the processor processes an update")
    val result = f.processor.process(
      InstanceOpProcessor.Operation(deadline, f.opSender.ref, task.taskId, InstanceUpdateOperation.ForceExpunge(task.taskId))
    )
    f.taskTrackerProbe.expectMsg(InstanceTrackerActor.StateChanged(expectedTaskChanged, ack))
    f.taskTrackerProbe.reply(())

    Then("it replies with unit immediately")
    result.futureValue should be(()) // first we make sure that the call completes

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("it calls expunge")
    verify(f.taskRepository).delete(task.taskId)

    And("it reloads the task")
    verify(f.taskRepository).get(task.taskId)

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process statusUpdate with NoChange") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a statusUpdateResolver and an update")
    val task = MarathonTestHelper.mininimalTask(appId)
    val taskProto = TaskSerializer.toProto(task)
    val stateOp = f.stateOpUpdate(task, MesosTaskStatusTestHelper.running)
    val expectedChange = InstanceUpdateEffect.Noop(task.taskId)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.get(task.taskId) returns Future.successful(Some(task))

    When("the processor processes an update")
    val result = f.processor.process(
      InstanceOpProcessor.Operation(deadline, testActor, task.taskId, stateOp)
    )

    Then("it replies with unit immediately")
    result.futureValue should be(())

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("the initiator gets its ack")
    expectMsg(expectedChange)

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process statusUpdate with Failure") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a statusUpdateResolver and an update")
    val task = MarathonTestHelper.mininimalTask(appId)
    val taskProto = TaskSerializer.toProto(task)
    val stateOp = f.stateOpReservationTimeout(task)
    val exception = new RuntimeException("ReservationTimeout on LaunchedEphemeral is unexpected")
    val expectedChange = InstanceUpdateEffect.Failure(exception)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedChange)
    f.taskRepository.get(task.taskId) returns Future.successful(Some(task))

    When("the processor processes an update")
    val result = f.processor.process(
      InstanceOpProcessor.Operation(deadline, testActor, task.taskId, stateOp)
    )

    Then("it replies with unit immediately")
    result.futureValue should be(())

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("the initiator gets its ack")
    expectMsg(Status.Failure(exception))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  class Fixture {
    lazy val config = MarathonTestHelper.defaultConfig()
    lazy val taskTrackerProbe = TestProbe()
    lazy val opSender = TestProbe()
    lazy val taskRepository = mock[TaskRepository]
    lazy val stateOpResolver = mock[InstanceOpProcessorImpl.TaskStateOpResolver]
    lazy val metrics = new Metrics(new MetricRegistry)
    lazy val clock = ConstantClock()
    lazy val now = clock.now()

    def stateOpLaunch(task: Task) = InstanceUpdateOperation.LaunchEphemeral(task)
    def stateOpUpdate(task: Task, mesosStatus: mesos.Protos.TaskStatus, now: Timestamp = now) = InstanceUpdateOperation.MesosUpdate(task, mesosStatus, now)
    def stateOpExpunge(task: Task) = InstanceUpdateOperation.ForceExpunge(task.taskId)
    def stateOpLaunchOnReservation(task: Task, status: Task.Status) = InstanceUpdateOperation.LaunchOnReservation(task.taskId, now, status, Seq.empty)
    def stateOpReservationTimeout(task: Task) = InstanceUpdateOperation.ReservationTimeout(task.taskId)
    def stateOpReserve(task: Task) = InstanceUpdateOperation.Reserve(task.asInstanceOf[Task.Reserved])

    lazy val healthCheckManager: HealthCheckManager = mock[HealthCheckManager]
    lazy val healthCheckManagerProvider: Provider[HealthCheckManager] = new Provider[HealthCheckManager] {
      override def get(): HealthCheckManager = healthCheckManager
    }
    lazy val schedulerActor: TestProbe = TestProbe()
    lazy val schedulerActorProvider = new Provider[ActorRef] {
      override def get(): ActorRef = schedulerActor.ref
    }
    lazy val appRepository: AppRepository = mock[AppRepository]
    lazy val appRepositoryProvider: Provider[ReadOnlyAppRepository] = new Provider[ReadOnlyAppRepository] {
      override def get(): AppRepository = appRepository
    }
    lazy val launchQueue: LaunchQueue = mock[LaunchQueue]
    lazy val launchQueueProvider: Provider[LaunchQueue] = new Provider[LaunchQueue] {
      override def get(): LaunchQueue = launchQueue
    }
    lazy val schedulerDriver: SchedulerDriver = mock[SchedulerDriver]
    lazy val eventBus: EventStream = mock[EventStream]
    lazy val taskUpdater: TaskUpdater = mock[TaskUpdater]
    lazy val taskStatusEmitter: TaskStatusEmitter = mock[TaskStatusEmitter]
    lazy val taskStatusEmitterProvider: Provider[TaskStatusEmitter] = new Provider[TaskStatusEmitter] {
      override def get(): TaskStatusEmitter = taskStatusEmitter
    }
    lazy val guiceModule = new CoreGuiceModule
    // Use module method to ensure that we keep the list of steps in sync with the test.
    lazy val statusUpdateSteps = guiceModule.taskStatusUpdateSteps(
      notifyHealthCheckManager,
      notifyRateLimiter,
      notifyLaunchQueue,
      emitUpdate,
      postToEventStream,
      scaleApp
    )

    // task status update steps
    lazy val notifyHealthCheckManager = new NotifyHealthCheckManagerStepImpl(healthCheckManagerProvider)
    lazy val notifyRateLimiter = new NotifyRateLimiterStepImpl(launchQueueProvider, appRepositoryProvider)
    lazy val postToEventStream = new PostToEventStreamStepImpl(eventBus, clock)
    lazy val notifyLaunchQueue = new NotifyLaunchQueueStepImpl(launchQueueProvider)
    lazy val emitUpdate = new TaskStatusEmitterPublishStepImpl(taskStatusEmitterProvider)
    lazy val scaleApp = new ScaleAppUpdateStepImpl(schedulerActorProvider)
    lazy val processor = new InstanceOpProcessorImpl(taskTrackerProbe.ref, taskRepository, stateOpResolver, config)

    def verifyNoMoreInteractions(): Unit = {
      taskTrackerProbe.expectNoMsg(0.seconds)
      noMoreInteractions(taskRepository)
      noMoreInteractions(stateOpResolver)
    }

    def toLaunched(instance: Instance, op: InstanceUpdateOperation.LaunchOnReservation): Instance = {
      instance.update(op) match {
        case InstanceUpdateEffect.Update(updatedInstance, _) => updatedInstance
        case _ => throw new scala.RuntimeException("op did not result in a launched instance")
      }
    }
  }
}
