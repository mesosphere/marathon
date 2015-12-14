package mesosphere.marathon.core.task.tracker.impl

import akka.actor.ActorSystem
import akka.event.EventStream
import akka.testkit.TestProbe
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.CoreGuiceModule
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.bus.{ TaskStatusEmitter, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.tracker.impl.steps._
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, AppRepository, PathId, Timestamp }
import mesosphere.marathon.tasks.{ TaskUpdater, TaskTracker, TaskIdUtil, TaskTrackerImpl }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonSpec, MarathonTestHelper }
import org.apache.mesos.SchedulerDriver
import org.mockito.ArgumentCaptor
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.concurrent.Future
import scala.concurrent.duration._

class TaskStatusUpdateProcessorImplTest
    extends MarathonSpec with Mockito with ScalaFutures with GivenWhenThen with Matchers {
  test("process update for unknown task that's not lost will result in a kill and ack") {
    fOpt = Some(new Fixture)
    val origUpdate = TaskStatusUpdateTestHelper.finished // everything != lost is handled in the same way
    val status = origUpdate.wrapped.status.mesosStatus.get.toBuilder.setTaskId(TaskIdUtil.newTaskId(appId)).build()
    val update = origUpdate.withTaskId(status.getTaskId)

    Given("an unknown task")
    f.taskTracker.getTask(appId, update.wrapped.taskId.getValue) returns None

    When("we process the updated")
    f.updateProcessor.publish(status).futureValue

    Then("we expect that the appropriate taskTracker methods have been called")
    verify(f.taskTracker).getTask(appId, update.wrapped.taskId.getValue)

    And("the task kill gets initiated")
    verify(f.schedulerDriver).killTask(status.getTaskId)
    And("the update has been acknowledged")
    verify(f.schedulerDriver).acknowledgeStatusUpdate(status)

    And("that's it")
    f.verifyNoMoreInteractions()
  }

  test("update for unknown task (TASK_LOST) will get only acknowledged") {
    fOpt = Some(new Fixture)

    val origUpdate = TaskStatusUpdateTestHelper.lost
    val status = origUpdate.wrapped.status.mesosStatus.get.toBuilder.setTaskId(TaskIdUtil.newTaskId(appId)).build()
    val update = origUpdate.withTaskId(status.getTaskId)

    Given("an unknown task")
    f.taskTracker.getTask(appId, update.wrapped.taskId.getValue) returns None

    When("we process the updated")
    f.updateProcessor.publish(status).futureValue

    Then("we expect that the appropriate taskTracker methods have been called")
    verify(f.taskTracker).getTask(appId, update.wrapped.taskId.getValue)

    And("the update has been acknowledged")
    verify(f.schedulerDriver).acknowledgeStatusUpdate(status)

    And("that's it")
    f.verifyNoMoreInteractions()
  }

  test("an update for existing task applies the side effects of all steps") {
    fOpt = Some(new Fixture)

    val origUpdate = TaskStatusUpdateTestHelper.finished
    val status = origUpdate.wrapped.status.mesosStatus.get.toBuilder.setTaskId(TaskIdUtil.newTaskId(appId)).build()
    val update = origUpdate.withTaskId(status.getTaskId)

    Given("a known task")
    f.taskTracker.getTask(appId, update.wrapped.taskId.getValue) returns Some(marathonTask)
    f.taskUpdater.statusUpdate(appId, status).asInstanceOf[Future[Unit]] returns Future.successful(())
    f.appRepository.app(appId, version) returns Future.successful(Some(app))
    And("and a cooperative launchQueue")
    f.launchQueue.notifyOfTaskUpdate(any) returns Future.successful(None)

    When("we process the updated")
    f.updateProcessor.publish(status).futureValue

    Then("we expect that the appropriate taskTracker methods have been called")
    verify(f.taskTracker).getTask(appId, update.wrapped.taskId.getValue)
    verify(f.taskUpdater).statusUpdate(appId, status)

    And("the healthCheckManager got informed")
    verify(f.healthCheckManager).update(status, version)
    And("an app scale check has been triggered")
    f.schedulerActor.expectMsg(ScaleApp(appId))
    And("the appRepository got queried")
    verify(f.appRepository).app(appId, version)
    And("the launch queue rate limiter got informed")
    verify(f.launchQueue).addDelay(app)
    And("the launch queue has been notified")
    verify(f.launchQueue).notifyOfTaskUpdate(any)
    And("the update has been acknowledged")
    verify(f.schedulerDriver).acknowledgeStatusUpdate(status)

    And("the appropriate event got published on the event stream")
    val eventCaptor = ArgumentCaptor.forClass(classOf[MesosStatusUpdateEvent])
    verify(f.eventBus).publish(eventCaptor.capture())
    eventCaptor.getValue should not be (null)
    eventCaptor.getValue.appId should equal (appId)

    And("that's it")
    f.verifyNoMoreInteractions()
  }

  var fOpt: Option[Fixture] = None
  def f = fOpt.get

  lazy val appId = PathId("/app")
  lazy val app = AppDefinition(appId)
  lazy val version = Timestamp.now()
  lazy val task = MarathonTestHelper.makeOneCPUTask(TaskIdUtil.newTaskId(appId).getValue).build()
  lazy val marathonTask =
    MarathonTask.newBuilder().setId(task.getTaskId.getValue).setVersion(version.toString).build()

  after {
    fOpt.foreach(_.shutdown())
  }

  class Fixture {
    implicit lazy val actorSystem: ActorSystem = ActorSystem()
    lazy val clock: ConstantClock = ConstantClock()
    lazy val taskStatusEmitter: TaskStatusEmitter = mock[TaskStatusEmitter]
    lazy val appRepository: AppRepository = mock[AppRepository]

    lazy val launchQueue: LaunchQueue = mock[LaunchQueue]
    lazy val eventBus: EventStream = mock[EventStream]
    lazy val schedulerActor: TestProbe = TestProbe()
    lazy val taskIdUtil: TaskIdUtil.type = TaskIdUtil
    lazy val healthCheckManager: HealthCheckManager = mock[HealthCheckManager]
    lazy val taskTracker: TaskTracker = mock[TaskTracker]
    lazy val taskUpdater: TaskUpdater = mock[TaskUpdater]
    lazy val schedulerDriver: SchedulerDriver = mock[SchedulerDriver]
    lazy val marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder = {
      val holder = new MarathonSchedulerDriverHolder
      holder.driver = Some(schedulerDriver)
      holder
    }

    lazy val notifyHealthCheckManager = new NotifyHealthCheckManagerStepImpl(healthCheckManager)
    lazy val notifyRateLimiter = new NotifyRateLimiterStepImpl(launchQueue, appRepository)
    lazy val updateTaskTrackerStep = new UpdateTaskTrackerStepImpl(taskUpdater)
    lazy val postToEventStream = new PostToEventStreamStepImpl(eventBus)
    lazy val notifyLaunchQueue = new NotifyLaunchQueueStepImpl(launchQueue)
    lazy val emitUpdate = new TaskStatusEmitterPublishStepImpl(taskStatusEmitter)
    lazy val scaleApp = new ScaleAppUpdateStepImpl(schedulerActor.ref)
    lazy val guiceModule = new CoreGuiceModule

    lazy val updateProcessor = new TaskStatusUpdateProcessorImpl(
      new Metrics(new MetricRegistry),
      clock,
      taskIdUtil,
      taskTracker,
      marathonSchedulerDriverHolder,
      // Use module method to ensure that we keep the list of steps in sync with the test.
      guiceModule.taskStatusUpdateSteps(
        notifyHealthCheckManager,
        notifyRateLimiter,
        updateTaskTrackerStep,
        notifyLaunchQueue,
        emitUpdate,
        postToEventStream,
        scaleApp
      )
    )

    def verifyNoMoreInteractions(): Unit = {
      noMoreInteractions(eventBus)
      noMoreInteractions(appRepository)
      noMoreInteractions(launchQueue)
      noMoreInteractions(healthCheckManager)
      noMoreInteractions(taskTracker)
      noMoreInteractions(schedulerDriver)

      shutdown()

      schedulerActor.expectNoMsg(0.seconds)
    }

    def shutdown(): Unit = {
      actorSystem.shutdown()
      actorSystem.awaitTermination()
      fOpt = None
    }
  }
}
