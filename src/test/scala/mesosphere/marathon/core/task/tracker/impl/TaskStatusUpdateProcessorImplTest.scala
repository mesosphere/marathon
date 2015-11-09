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
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.core.task.bus.{ TaskStatusEmitter, TaskStatusUpdateTestHelper }
import mesosphere.marathon.core.task.tracker.TaskStatusUpdateProcessor
import mesosphere.marathon.core.task.tracker.impl.steps._
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, AppRepository, PathId, Timestamp }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import mesosphere.marathon.{ MarathonSchedulerDriverHolder, MarathonSpec, MarathonTestHelper }
import org.apache.mesos.Protos.TaskState
import org.apache.mesos.SchedulerDriver
import org.mockito.{ Matchers, ArgumentCaptor, Mockito }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class TaskStatusUpdateProcessorImplTest extends MarathonSpec {

  for (
    origUpdate <- Seq(
      TaskStatusUpdateTestHelper.finished,
      TaskStatusUpdateTestHelper.lost,
      TaskStatusUpdateTestHelper.killed,
      TaskStatusUpdateTestHelper.error
    )
  ) {
    test(s"Remove terminated task (${origUpdate.wrapped.status.getClass.getSimpleName})") {
      val status = origUpdate.wrapped.status.mesosStatus.get.toBuilder.setTaskId(TaskIdUtil.newTaskId(appId)).build()
      val update = origUpdate.withTaskId(status.getTaskId)

      Mockito.when(taskTracker.fetchTask(update.wrapped.taskId.getValue))
        .thenReturn(Some(marathonTask))
      Mockito.when(taskTracker.terminated(appId, update.wrapped.taskId.getValue))
        .thenReturn(Future.successful(Some(marathonTask)))
      Mockito.when(appRepository.app(appId, version)).thenReturn(Future.successful(Some(app)))
      Mockito.when(launchQueue.notifyOfTaskUpdate(Matchers.any())).thenReturn(Future.successful(None))

      Await.result(updateProcessor.publish(status), 3.seconds)

      Mockito.verify(taskTracker).fetchTask(update.wrapped.taskId.getValue)
      Mockito.verify(healthCheckManager).update(status, version)
      Mockito.verify(taskTracker).terminated(appId, update.wrapped.taskId.getValue)
      schedulerActor.expectMsg(ScaleApp(appId))
      Mockito.verify(schedulerDriver).acknowledgeStatusUpdate(status)
      Mockito.verify(launchQueue).notifyOfTaskUpdate(Matchers.any())

      if (update.wrapped.status.mesosStatus.forall(_.getState != TaskState.TASK_KILLED)) {
        Mockito.verify(appRepository).app(appId, version)
        Mockito.verify(launchQueue).addDelay(app)
      }

      val eventCaptor = ArgumentCaptor.forClass(classOf[MesosStatusUpdateEvent])
      Mockito.verify(eventBus).publish(eventCaptor.capture())
      assert(eventCaptor.getValue != null)
      assert(eventCaptor.getValue.appId == appId)
    }
  }

  private[this] lazy val appId = PathId("/app")
  private[this] lazy val app = AppDefinition(appId)
  private[this] lazy val version = Timestamp.now()
  private[this] lazy val task = MarathonTestHelper.makeOneCPUTask(TaskIdUtil.newTaskId(appId).getValue).build()
  private[this] lazy val marathonTask =
    MarathonTask.newBuilder().setId(task.getTaskId.getValue).setVersion(version.toString).build()

  private[this] implicit var actorSystem: ActorSystem = _
  private[this] var clock: ConstantClock = _
  private[this] var taskStatusEmitter: TaskStatusEmitter = _
  private[this] var appRepository: AppRepository = _
  private[this] var launchQueue: LaunchQueue = _
  private[this] var eventBus: EventStream = _
  private[this] var schedulerActor: TestProbe = _
  private[this] var taskIdUtil: TaskIdUtil = _
  private[this] var healthCheckManager: HealthCheckManager = _
  private[this] var taskTracker: TaskTracker = _
  private[this] var schedulerDriver: SchedulerDriver = _
  private[this] var marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder = _
  private[this] var updateProcessor: TaskStatusUpdateProcessor = _

  before {
    actorSystem = ActorSystem()
    clock = ConstantClock()
    taskStatusEmitter = mock[TaskStatusEmitter]
    appRepository = mock[AppRepository]
    launchQueue = mock[LaunchQueue]
    eventBus = mock[EventStream]
    schedulerActor = TestProbe()
    taskIdUtil = TaskIdUtil
    healthCheckManager = mock[HealthCheckManager]
    taskTracker = mock[TaskTracker]
    schedulerDriver = mock[SchedulerDriver]
    marathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder
    marathonSchedulerDriverHolder.driver = Some(schedulerDriver)

    val notifyHealthCheckManager = new NotifyHealthCheckManagerStepImpl(healthCheckManager)

    val notifyRateLimiter = new NotifyRateLimiterStepImpl(launchQueue, appRepository)

    val updateTaskTrackerStep = new UpdateTaskTrackerStepImpl(taskTracker)

    val postToEventStream = new PostToEventStreamStepImpl(eventBus)

    val notifyLaunchQueue = new NotifyLaunchQueueStepImpl(launchQueue)

    val emitUpdate = new TaskStatusEmitterPublishStepImpl(taskStatusEmitter)

    val scaleApp = new ScaleAppUpdateStepImpl(schedulerActor.ref)

    val guiceModule = new CoreGuiceModule

    updateProcessor = new TaskStatusUpdateProcessorImpl(
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
  }

  after {
    Mockito.verifyNoMoreInteractions(eventBus)
    Mockito.verifyNoMoreInteractions(appRepository)
    Mockito.verifyNoMoreInteractions(launchQueue)
    Mockito.verifyNoMoreInteractions(healthCheckManager)
    Mockito.verifyNoMoreInteractions(taskTracker)
    Mockito.verifyNoMoreInteractions(schedulerDriver)

    actorSystem.shutdown()
    actorSystem.awaitTermination()

    schedulerActor.expectNoMsg(0.seconds)
  }
}
