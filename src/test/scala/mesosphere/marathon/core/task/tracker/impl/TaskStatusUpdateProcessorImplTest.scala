package mesosphere.marathon.core.task.tracker.impl

import akka.actor.ActorSystem
import akka.event.EventStream
import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.PreparationMessages
import mesosphere.marathon.core.task.bus.{ TaskStatusEmitter, TaskStatusUpdateTestHelper, TaskStatusObservables }
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.core.task.tracker.TaskStatusUpdateProcessor
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ AppDefinition, AppRepository, Timestamp, PathId }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import mesosphere.marathon.{ MarathonTestHelper, MarathonSchedulerDriverHolder, MarathonSpec }
import org.apache.mesos.Protos.{ TaskState, TaskStatus }
import org.apache.mesos.SchedulerDriver
import org.mockito.internal.matchers.CapturingMatcher
import org.mockito.{ ArgumentCaptor, Matchers, Mockito }
import rx.lang.scala.Subject
import rx.lang.scala.subjects.PublishSubject

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class TaskStatusUpdateProcessorImplTest extends MarathonSpec {

  for (
    update <- Seq(
      TaskStatusUpdateTestHelper.finished,
      TaskStatusUpdateTestHelper.lost,
      TaskStatusUpdateTestHelper.killed,
      TaskStatusUpdateTestHelper.error
    ).map(_.withAppId(appId.toString))
  ) {
    test(s"Remove terminated task (${update.wrapped.status.getClass.getSimpleName})") {

      Mockito.when(taskTracker.fetchTask(appId, update.wrapped.taskId.getValue))
        .thenReturn(Some(marathonTask))
      Mockito.when(taskTracker.terminated(appId, update.wrapped.taskId.getValue))
        .thenReturn(Future.successful(Some(marathonTask)))
      Mockito.when(appRepository.app(appId, version)).thenReturn(Future.successful(Some(app)))

      Await.result(updateProcessor.publish(update.wrapped), 3.seconds)

      Mockito.verify(taskTracker).fetchTask(appId, update.wrapped.taskId.getValue)
      val status: TaskStatus = update.wrapped.status.mesosStatus.get
      Mockito.verify(healthCheckManager).update(status, version)
      Mockito.verify(taskTracker).terminated(appId, update.wrapped.taskId.getValue)
      schedulerActor.expectMsg(ScaleApp(appId))
      Mockito.verify(schedulerDriver).acknowledgeStatusUpdate(status)

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

    updateProcessor = new TaskStatusUpdateProcessorImpl(
      taskStatusEmitter,
      appRepository,
      launchQueue,
      eventBus,
      schedulerActor.ref,
      taskIdUtil,
      healthCheckManager,
      taskTracker,
      marathonSchedulerDriverHolder
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
