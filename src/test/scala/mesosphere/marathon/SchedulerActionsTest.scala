package mesosphere.marathon

import akka.actor.ActorSystem
import akka.testkit.{ TestKit, TestProbe }
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ AppDefinition, AppRepository, GroupRepository, PathId }
import mesosphere.marathon.tasks.{ TaskReconciler, TaskTracker, TaskTrackerImpl, TaskTrackerImpl$ }
import mesosphere.mesos.protos
import mesosphere.mesos.protos.Implicits.{ slaveIDToProto, taskIDToProto }
import mesosphere.mesos.protos.SlaveID
import org.apache.mesos.Protos.{ TaskID, TaskState, TaskStatus }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ times, verify, when, verifyNoMoreInteractions }
import org.scalatest.Matchers
import org.scalatest.mock.MockitoSugar

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class SchedulerActionsTest extends TestKit(ActorSystem("TestSystem")) with MarathonSpec with Matchers with MockitoSugar {
  import system.dispatcher

  test("Reset rate limiter if application is stopped") {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskReconciler = mock[TaskReconciler]

    val scheduler = new SchedulerActions(
      repo,
      mock[GroupRepository],
      mock[HealthCheckManager],
      taskReconciler,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))

    when(repo.expunge(app.id)).thenReturn(Future.successful(Seq(true)))
    when(taskReconciler.getTasks(app.id)).thenReturn(Set.empty[Protos.MarathonTask])

    val res = scheduler.stopApp(mock[SchedulerDriver], app)

    Await.ready(res, 1.second)

    verify(queue).purge(app.id)
    verify(queue).resetDelay(app)
    verifyNoMoreInteractions(queue)
  }

  test("Task reconciliation sends known running and staged tasks and empty list") {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskReconciler = mock[TaskReconciler]
    val driver = mock[SchedulerDriver]

    val runningStatus = TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue("task_1"))
      .setState(TaskState.TASK_RUNNING)
      .build()

    val runningTask = MarathonTask.newBuilder
      .setId("task_1")
      .setStatus(runningStatus)
      .build()

    val stagedTask = MarathonTask.newBuilder
      .setId("task_2")
      .build()

    val stagedStatus = TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue(stagedTask.getId))
      .setState(TaskState.TASK_STAGING)
      .build()

    val stagedTaskWithSlaveId = MarathonTask.newBuilder
      .setId("task_3")
      .setSlaveId(SlaveID("slave 1"))
      .build()

    val stagedWithSlaveIdStatus = TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue(stagedTaskWithSlaveId.getId))
      .setSlaveId(stagedTaskWithSlaveId.getSlaveId)
      .setState(TaskState.TASK_STAGING)
      .build()

    val scheduler = new SchedulerActions(
      repo,
      mock[GroupRepository],
      mock[HealthCheckManager],
      taskReconciler,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))

    when(taskReconciler.getTasks(app.id)).thenReturn(Set(runningTask, stagedTask, stagedTaskWithSlaveId))
    when(repo.allPathIds()).thenReturn(Future.successful(Seq(app.id)))
    when(taskReconciler.list).thenReturn(Map(
      app.id -> TaskTracker.App(app.id, Set(runningTask, stagedTask, stagedTaskWithSlaveId), shutdown = false)
    ))

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver).reconcileTasks(Set(runningStatus, stagedStatus, stagedWithSlaveIdStatus).asJava)
    verify(driver).reconcileTasks(java.util.Arrays.asList())
  }

  test("Task reconciliation only one empty list, when no tasks are present in Marathon") {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskReconciler = mock[TaskReconciler]
    val driver = mock[SchedulerDriver]

    val scheduler = new SchedulerActions(
      repo,
      mock[GroupRepository],
      mock[HealthCheckManager],
      taskReconciler,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))

    when(taskReconciler.getTasks(app.id)).thenReturn(Set.empty[MarathonTask])
    when(repo.allPathIds()).thenReturn(Future.successful(Seq()))
    when(taskReconciler.list).thenReturn(Map.empty[PathId, TaskTracker.App])

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver, times(1)).reconcileTasks(java.util.Arrays.asList())
  }

  test("Kill orphaned task") {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskReconciler = mock[TaskReconciler]
    val driver = mock[SchedulerDriver]

    val status = TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue("task_1"))
      .setState(TaskState.TASK_RUNNING)
      .build()

    val task = MarathonTask.newBuilder
      .setId("task_1")
      .setStatus(status)
      .build()

    val orphanedTask = MarathonTask.newBuilder
      .setId("orphaned task")
      .setStatus(status)
      .build()

    val scheduler = new SchedulerActions(
      repo,
      mock[GroupRepository],
      mock[HealthCheckManager],
      taskReconciler,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))
    val orphanedApp = AppDefinition(id = PathId("/orphan"))

    when(taskReconciler.getTasks(app.id)).thenReturn(Set(task))
    when(taskReconciler.getTasks(orphanedApp.id)).thenReturn(Set(orphanedTask))
    when(repo.allPathIds()).thenReturn(Future.successful(Seq(app.id)))
    when(taskReconciler.list).thenReturn(Map(
      app.id -> TaskTracker.App(app.id, Set(task), shutdown = false),
      orphanedApp.id -> TaskTracker.App(orphanedApp.id, Set(orphanedTask, task), shutdown = false)
    ))

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver, times(1)).killTask(protos.TaskID(orphanedTask.getId))
    verify(taskReconciler, times(1)).removeUnknownAppAndItsTasks(orphanedApp.id)
  }
}
