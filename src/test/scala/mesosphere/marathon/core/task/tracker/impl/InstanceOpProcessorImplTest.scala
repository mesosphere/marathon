package mesosphere.marathon.core.task.tracker.impl

import akka.Done
import akka.actor.{ ActorRef, Status }
import akka.event.EventStream
import akka.testkit.TestProbe
import ch.qos.logback.classic.Level
import com.codahale.metrics.MetricRegistry
import com.google.inject.Provider
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.CoreGuiceModule
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{ InstanceUpdateEffect, InstanceUpdateOperation }
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.{ MesosTaskStatusTestHelper, TaskStatusEmitter }
import mesosphere.marathon.core.task.tracker.TaskUpdater
import mesosphere.marathon.core.task.update.impl.steps.{ NotifyHealthCheckManagerStepImpl, NotifyLaunchQueueStepImpl, NotifyRateLimiterStepImpl, PostToEventStreamStepImpl, ScaleAppUpdateStepImpl, TaskStatusEmitterPublishStepImpl }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.storage.repository.{ AppRepository, InstanceRepository, ReadOnlyAppRepository }
import mesosphere.marathon.test.{ CaptureLogEvents, MarathonActorSupport, Mockito, _ }
import org.apache.mesos.SchedulerDriver
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ GivenWhenThen, Matchers }

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
    val builder = TestInstanceBuilder.newBuilderWithLaunchedTask(appId)
    val instance = builder.getInstance()
    val stateOp = builder.stateOpUpdate(MesosTaskStatusTestHelper.runningHealthy())
    val mesosStatus = stateOp.mesosStatus
    val expectedEffect = InstanceUpdateEffect.Update(instance, Some(instance), events = Nil)
    val ack = InstanceTrackerActor.Ack(f.opSender.ref, expectedEffect)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedEffect)
    f.instanceRepository.get(instance.instanceId) returns Future.successful(Some(instance))
    f.instanceRepository.store(instance) returns Future.successful(Done)
    f.taskUpdater.statusUpdate(appId, mesosStatus).asInstanceOf[Future[Unit]] returns Future.successful(())

    When("the processor processes an update")
    val result = f.processor.process(
      InstanceOpProcessor.Operation(deadline, f.opSender.ref, instance.instanceId, stateOp)
    )

    And("the taskTracker replies immediately")
    f.instanceTrackerProbe.expectMsg(InstanceTrackerActor.StateChanged(ack))
    f.instanceTrackerProbe.reply(())

    And("the processor replies with unit accordingly")
    result.futureValue should be(()) // first wait for the call to complete

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("it calls store")
    verify(f.instanceRepository).store(instance)

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with failing taskRepository.store but successful load of existing task") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository and existing task")
    val builder = TestInstanceBuilder.newBuilderWithLaunchedTask(appId)
    val instance = builder.getInstance()
    val stateOp = builder.stateOpUpdate(MesosTaskStatusTestHelper.running())
    val expectedEffect = InstanceUpdateEffect.Update(instance, Some(instance), events = Nil)
    val ack = InstanceTrackerActor.Ack(f.opSender.ref, expectedEffect)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedEffect)
    f.instanceRepository.store(instance) returns Future.failed(new RuntimeException("fail"))
    f.instanceRepository.get(instance.instanceId) returns Future.successful(Some(instance))

    When("the processor processes an update")
    var result: Try[Unit] = Failure(new RuntimeException("test executing failed"))
    val logs = CaptureLogEvents.forBlock {
      val resultF = f.processor.process(
        InstanceOpProcessor.Operation(deadline, f.opSender.ref, instance.instanceId, stateOp)
      )
      f.instanceTrackerProbe.expectMsg(InstanceTrackerActor.StateChanged(ack))
      f.instanceTrackerProbe.reply(())
      result = Try(resultF.futureValue) // linter:ignore:VariableAssignedUnusedValue // we need to complete the future here to get all the logs
    }

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    Then("it calls store")
    verify(f.instanceRepository).store(instance)

    And("logs a warning after detecting the error")
    logs.filter(l => l.getLevel == Level.WARN && l.getMessage.contains(s"[${instance.instanceId.idString}]")) should have size 1

    And("loads the task")
    verify(f.instanceRepository).get(instance.instanceId)

    And("it replies with unit immediately because the task is as expected")
    result should be(Success(()))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with failing taskRepository.store and successful load of non-existing task") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository and no task")
    val builder = TestInstanceBuilder.newBuilderWithLaunchedTask(appId)
    val instance = builder.getInstance()
    val stateOp = builder.stateOpUpdate(MesosTaskStatusTestHelper.running())
    val expectedEffect = InstanceUpdateEffect.Update(instance, Some(instance), events = Nil)
    val storeException: RuntimeException = new scala.RuntimeException("fail")
    val ack = InstanceTrackerActor.Ack(f.opSender.ref, InstanceUpdateEffect.Failure(storeException))
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedEffect)
    f.instanceRepository.store(instance) returns Future.failed(storeException)
    f.instanceRepository.get(instance.instanceId) returns Future.successful(None)

    When("the processor processes an update")

    var result: Try[Unit] = Failure(new RuntimeException("test executing failed"))
    val logs = CaptureLogEvents.forBlock {
      val resultF = f.processor.process(
        InstanceOpProcessor.Operation(deadline, f.opSender.ref, instance.instanceId, stateOp)
      )
      f.instanceTrackerProbe.expectMsg(InstanceTrackerActor.StateChanged(ack))
      f.instanceTrackerProbe.reply(())
      result = Try(resultF.futureValue) // linter:ignore:VariableAssignedUnusedValue // we need to complete the future here to get all the logs
    }

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    Then("it calls store")
    verify(f.instanceRepository).store(instance)

    And("logs a warning after detecting the error")
    logs.filter(l => l.getLevel == Level.WARN && l.getMessage.contains(s"[${instance.instanceId.idString}]")) should have size 1

    And("loads the task")
    verify(f.instanceRepository).get(instance.instanceId)

    And("it replies with unit immediately because the task is as expected")
    result should be(Success(()))

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process update with failing taskRepository.store and load also fails") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository and existing task")
    val builder = TestInstanceBuilder.newBuilderWithLaunchedTask(appId)
    val instance = builder.getInstance()
    val storeFailed: RuntimeException = new scala.RuntimeException("store failed")
    val stateOp = builder.stateOpUpdate(MesosTaskStatusTestHelper.running())
    val expectedEffect = InstanceUpdateEffect.Update(instance, Some(instance), events = Nil)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedEffect)
    f.instanceRepository.store(instance) returns Future.failed(storeFailed)
    f.instanceRepository.get(instance.instanceId) returns Future.failed(new RuntimeException("task failed"))

    When("the processor processes an update")
    var result: Try[Unit] = Failure(new RuntimeException("test executing failed"))
    val logs = CaptureLogEvents.forBlock {
      result = Try(f.processor.process( // linter:ignore:VariableAssignedUnusedValue
        InstanceOpProcessor.Operation(deadline, f.opSender.ref, instance.instanceId, stateOp)
      ).futureValue) // we need to complete the future here to get all the logs
    }

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    Then("it calls store")
    verify(f.instanceRepository).store(instance)

    And("loads the task")
    verify(f.instanceRepository).get(instance.instanceId)

    And("it replies with the original error")
    result.isFailure shouldBe true
    result.failed.get.getCause.getMessage should be(storeFailed.getMessage)

    And("logs a two warnings, for store and for task")
    logs.filter(l => l.getLevel == Level.WARN && l.getMessage.contains(s"[${instance.instanceId.idString}]")) should have size 2

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process expunge with success") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val builder = TestInstanceBuilder.newBuilderWithLaunchedTask(appId)
    val instance = builder.getInstance()
    val stateOp = builder.stateOpExpunge()
    val expectedEffect = InstanceUpdateEffect.Expunge(instance, events = Nil)
    val ack = InstanceTrackerActor.Ack(f.opSender.ref, expectedEffect)

    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedEffect)
    f.instanceRepository.delete(instance.instanceId) returns Future.successful(Done)

    When("the processor processes an update")
    val result = f.processor.process(
      InstanceOpProcessor.Operation(deadline, f.opSender.ref, instance.instanceId, InstanceUpdateOperation.ForceExpunge(instance.instanceId))
    )
    f.instanceTrackerProbe.expectMsg(InstanceTrackerActor.StateChanged(ack))
    f.instanceTrackerProbe.reply(())

    Then("it replies with unit immediately")
    result.futureValue should be(())

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("it calls expunge")
    verify(f.instanceRepository).delete(instance.instanceId)

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process expunge, expunge fails but task reload confirms that task is gone") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val builder = TestInstanceBuilder.newBuilderWithLaunchedTask(appId)
    val instance = builder.getInstance()
    val stateOp = builder.stateOpExpunge()
    val expectedEffect = InstanceUpdateEffect.Expunge(instance, events = Nil)
    val ack = InstanceTrackerActor.Ack(f.opSender.ref, expectedEffect)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedEffect)
    f.instanceRepository.delete(instance.instanceId) returns Future.failed(new RuntimeException("expunge fails"))
    f.instanceRepository.get(instance.instanceId) returns Future.successful(None)

    When("the processor processes an update")
    val result = f.processor.process(
      InstanceOpProcessor.Operation(deadline, f.opSender.ref, instance.instanceId, InstanceUpdateOperation.ForceExpunge(instance.instanceId))
    )
    f.instanceTrackerProbe.expectMsg(InstanceTrackerActor.StateChanged(ack))
    f.instanceTrackerProbe.reply(())

    Then("it replies with unit immediately")
    result.futureValue should be(())

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("it calls expunge")
    verify(f.instanceRepository).delete(instance.instanceId)

    And("it reloads the task")
    verify(f.instanceRepository).get(instance.instanceId)

    And("the taskTracker gets the update")

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process expunge, expunge fails and task reload suggests that task is still there") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a taskRepository")
    val builder = TestInstanceBuilder.newBuilderWithLaunchedTask(appId)
    val instance = builder.getInstance()
    val expungeException: RuntimeException = new scala.RuntimeException("expunge fails")
    val stateOp = builder.stateOpExpunge()
    val resolvedEffect = InstanceUpdateEffect.Expunge(instance, events = Nil)
    val ack = InstanceTrackerActor.Ack(f.opSender.ref, InstanceUpdateEffect.Failure(expungeException))
    f.stateOpResolver.resolve(stateOp) returns Future.successful(resolvedEffect)
    f.instanceRepository.delete(instance.instanceId) returns Future.failed(expungeException)
    f.instanceRepository.get(instance.instanceId) returns Future.successful(Some(instance))

    When("the processor processes an update")
    val result = f.processor.process(
      InstanceOpProcessor.Operation(deadline, f.opSender.ref, instance.instanceId, InstanceUpdateOperation.ForceExpunge(instance.instanceId))
    )
    f.instanceTrackerProbe.expectMsg(InstanceTrackerActor.StateChanged(ack))
    f.instanceTrackerProbe.reply(())

    Then("it replies with unit immediately")
    result.futureValue should be(()) // first we make sure that the call completes

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("it calls expunge")
    verify(f.instanceRepository).delete(instance.instanceId)

    And("it reloads the task")
    verify(f.instanceRepository).get(instance.instanceId)

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process statusUpdate with NoChange") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a statusUpdateResolver and an update")
    val builder = TestInstanceBuilder.newBuilderWithLaunchedTask(appId)
    val instance = builder.getInstance()
    val stateOp = builder.stateOpUpdate(MesosTaskStatusTestHelper.running())
    val expectedEffect = InstanceUpdateEffect.Noop(instance.instanceId)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedEffect)
    f.instanceRepository.get(instance.instanceId) returns Future.successful(Some(instance))

    When("the processor processes an update")
    val result = f.processor.process(
      InstanceOpProcessor.Operation(deadline, testActor, instance.instanceId, stateOp)
    )

    Then("it replies with unit immediately")
    result.futureValue should be(())

    Then("The StateOpResolver is called")
    verify(f.stateOpResolver).resolve(stateOp)

    And("the initiator gets its ack")
    expectMsg(expectedEffect)

    And("no more interactions")
    f.verifyNoMoreInteractions()
  }

  test("process statusUpdate with Failure") {
    val f = new Fixture
    val appId = PathId("/app")

    Given("a statusUpdateResolver and an update")
    val builder = TestInstanceBuilder.newBuilderWithLaunchedTask(appId)
    val instance = builder.getInstance()
    val stateOp = builder.stateOpReservationTimeout()
    val exception = new RuntimeException("ReservationTimeout on LaunchedEphemeral is unexpected")
    val expectedEffect = InstanceUpdateEffect.Failure(exception)
    f.stateOpResolver.resolve(stateOp) returns Future.successful(expectedEffect)
    f.instanceRepository.get(instance.instanceId) returns Future.successful(Some(instance))

    When("the processor processes an update")
    val result = f.processor.process(
      InstanceOpProcessor.Operation(deadline, testActor, instance.instanceId, stateOp)
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
    lazy val instanceTrackerProbe = TestProbe()
    lazy val opSender = TestProbe()
    lazy val instanceRepository = mock[InstanceRepository]
    lazy val stateOpResolver = mock[InstanceOpProcessorImpl.InstanceUpdateOpResolver]
    lazy val metrics = new Metrics(new MetricRegistry)
    lazy val clock = ConstantClock()
    lazy val now = clock.now()

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
    lazy val postToEventStream = new PostToEventStreamStepImpl(eventBus)
    lazy val notifyLaunchQueue = new NotifyLaunchQueueStepImpl(launchQueueProvider)
    lazy val emitUpdate = new TaskStatusEmitterPublishStepImpl(taskStatusEmitterProvider)
    lazy val scaleApp = new ScaleAppUpdateStepImpl(schedulerActorProvider)
    lazy val processor = new InstanceOpProcessorImpl(instanceTrackerProbe.ref, instanceRepository, stateOpResolver, config)

    def verifyNoMoreInteractions(): Unit = {
      instanceTrackerProbe.expectNoMsg(0.seconds)
      noMoreInteractions(instanceRepository)
      noMoreInteractions(stateOpResolver)
    }

    def toLaunched(instance: Instance, op: InstanceUpdateOperation.LaunchOnReservation): Instance = {
      instance.update(op) match {
        case InstanceUpdateEffect.Update(updatedInstance, _, _) => updatedInstance
        case _ => throw new scala.RuntimeException("op did not result in a launched instance")
      }
    }
  }
}
