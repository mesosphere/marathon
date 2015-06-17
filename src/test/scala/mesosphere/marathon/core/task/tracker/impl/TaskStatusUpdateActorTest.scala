package mesosphere.marathon.core.task.tracker.impl

import akka.actor.ActorSystem
import akka.event.EventStream
import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.marathon.MarathonSchedulerActor.ScaleApp
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.leadership.PreparationMessages
import mesosphere.marathon.core.task.bus.{ TaskStatusUpdateTestHelper, TaskStatusObservables }
import mesosphere.marathon.core.task.bus.TaskStatusObservables.TaskStatusUpdate
import mesosphere.marathon.event.MesosStatusUpdateEvent
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ Timestamp, PathId }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskTracker }
import mesosphere.marathon.{ MarathonTestHelper, MarathonSchedulerDriverHolder, MarathonSpec }
import org.apache.mesos.Protos.TaskStatus
import org.apache.mesos.SchedulerDriver
import org.mockito.internal.matchers.CapturingMatcher
import org.mockito.{ ArgumentCaptor, Matchers, Mockito }
import rx.lang.scala.Subject
import rx.lang.scala.subjects.PublishSubject

import scala.concurrent.Future
import scala.concurrent.duration._

class TaskStatusUpdateActorTest extends MarathonSpec {

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

      val probe = TestProbe()
      probe.send(statusUpdateRef, PreparationMessages.PrepareForStart)
      probe.expectMsg(PreparationMessages.Prepared(statusUpdateRef))

      allAppsStatus.onNext(update.wrapped)

      Mockito.verify(taskTracker).fetchTask(appId, update.wrapped.taskId.getValue)
      val status: TaskStatus = update.wrapped.status.mesosStatus.get
      Mockito.verify(healthCheckManager).update(status, version)
      Mockito.verify(taskTracker).terminated(appId, update.wrapped.taskId.getValue)
      schedulerActor.expectMsg(ScaleApp(appId))
      Mockito.verify(schedulerDriver).acknowledgeStatusUpdate(status)

      val eventCaptor = ArgumentCaptor.forClass(classOf[MesosStatusUpdateEvent])
      Mockito.verify(eventBus).publish(eventCaptor.capture())
      assert(eventCaptor.getValue != null)
      assert(eventCaptor.getValue.appId == appId)
    }
  }

  private[this] lazy val appId = PathId("/app")
  private[this] lazy val version = Timestamp.now()
  private[this] lazy val task = MarathonTestHelper.makeOneCPUTask(TaskIdUtil.newTaskId(appId).getValue).build()
  private[this] lazy val marathonTask =
    MarathonTask.newBuilder().setId(task.getTaskId.getValue).setVersion(version.toString).build()

  private[this] implicit var actorSystem: ActorSystem = _
  private[this] var allAppsStatus: Subject[TaskStatusUpdate] = _
  private[this] var taskStatusObservable: TaskStatusObservables = _
  private[this] var eventBus: EventStream = _
  private[this] var schedulerActor: TestProbe = _
  private[this] var taskIdUtil: TaskIdUtil = _
  private[this] var healthCheckManager: HealthCheckManager = _
  private[this] var taskTracker: TaskTracker = _
  private[this] var schedulerDriver: SchedulerDriver = _
  private[this] var marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder = _
  private[this] var statusUpdateRef: TestActorRef[_] = _

  before {
    actorSystem = ActorSystem()
    allAppsStatus = PublishSubject()
    taskStatusObservable = mock[TaskStatusObservables]
    Mockito.when(taskStatusObservable.forAll).thenReturn(allAppsStatus)
    eventBus = mock[EventStream]
    schedulerActor = TestProbe()
    taskIdUtil = TaskIdUtil
    healthCheckManager = mock[HealthCheckManager]
    taskTracker = mock[TaskTracker]
    schedulerDriver = mock[SchedulerDriver]
    marathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder
    marathonSchedulerDriverHolder.driver = Some(schedulerDriver)

    statusUpdateRef = TestActorRef(new TaskStatusUpdateActor(
      taskStatusObservable,
      eventBus,
      schedulerActor.ref,
      taskIdUtil,
      healthCheckManager,
      taskTracker,
      marathonSchedulerDriverHolder
    ))

    statusUpdateRef.start()
  }

  after {
    Mockito.verifyNoMoreInteractions(eventBus)
    Mockito.verifyNoMoreInteractions(healthCheckManager)
    Mockito.verifyNoMoreInteractions(taskTracker)
    Mockito.verifyNoMoreInteractions(schedulerDriver)

    actorSystem.shutdown()
    actorSystem.awaitTermination()

    schedulerActor.expectNoMsg(0.seconds)
  }
}
