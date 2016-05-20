package mesosphere.marathon

import akka.testkit.TestProbe
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.TaskTracker.{ AppTasks, TasksByApp }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ AppDefinition, AppRepository, GroupRepository, PathId }
import mesosphere.marathon.test.MarathonActorSupport
import mesosphere.mesos.protos
import mesosphere.mesos.protos.Implicits.{ slaveIDToProto, taskIDToProto }
import mesosphere.mesos.protos.SlaveID
import org.apache.mesos.Protos.{ TaskID, TaskState, TaskStatus }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ times, verify, verifyNoMoreInteractions, when }
import org.scalatest.{ GivenWhenThen, Matchers }
import org.scalatest.mock.MockitoSugar

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class SchedulerActionsTest extends MarathonActorSupport with MarathonSpec with Matchers with MockitoSugar {
  import system.dispatcher

  test("Reset rate limiter if application is stopped") {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskTracker = mock[TaskTracker]

    val scheduler = new SchedulerActions(
      repo,
      mock[GroupRepository],
      mock[HealthCheckManager],
      taskTracker,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))

    when(repo.expunge(app.id)).thenReturn(Future.successful(Seq(true)))
    when(taskTracker.appTasksSync(app.id)).thenReturn(Set.empty[Protos.MarathonTask])

    val res = scheduler.stopApp(mock[SchedulerDriver], app)

    Await.ready(res, 1.second)

    verify(queue).purge(app.id)
    verify(queue).resetDelay(app)
    verifyNoMoreInteractions(queue)
  }

  test("Task reconciliation sends known running and staged tasks and empty list") {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskTracker = mock[TaskTracker]
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
      taskTracker,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))

    val tasks = Set(runningTask, stagedTask, stagedTaskWithSlaveId)
    when(taskTracker.tasksByApp()).thenReturn(Future.successful(TasksByApp.of(AppTasks(app.id, tasks))))
    when(repo.allPathIds()).thenReturn(Future.successful(Seq(app.id)))

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver).reconcileTasks(Set(runningStatus, stagedStatus, stagedWithSlaveIdStatus).asJava)
    verify(driver).reconcileTasks(java.util.Arrays.asList())
  }

  test("Task reconciliation only one empty list, when no tasks are present in Marathon") {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskTracker = mock[TaskTracker]
    val driver = mock[SchedulerDriver]

    val scheduler = new SchedulerActions(
      repo,
      mock[GroupRepository],
      mock[HealthCheckManager],
      taskTracker,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))

    when(taskTracker.tasksByApp()).thenReturn(Future.successful(TasksByApp.empty))
    when(repo.allPathIds()).thenReturn(Future.successful(Seq()))

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver, times(1)).reconcileTasks(java.util.Arrays.asList())
  }

  test("Kill orphaned task") {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskTracker = mock[TaskTracker]
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
      taskTracker,
      queue,
      system.eventStream,
      TestProbe().ref,
      mock[MarathonConf]
    )

    val app = AppDefinition(id = PathId("/myapp"))
    val tasksOfApp = AppTasks(app.id, Iterable(task))
    val orphanedApp = AppDefinition(id = PathId("/orphan"))
    val tasksOfOrphanedApp = AppTasks(orphanedApp.id, Iterable(orphanedTask))

    when(taskTracker.tasksByApp()).thenReturn(Future.successful(TasksByApp.of(tasksOfApp, tasksOfOrphanedApp)))
    when(repo.allPathIds()).thenReturn(Future.successful(Seq(app.id)))

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver, times(1)).killTask(protos.TaskID(orphanedTask.getId))
  }

  ignore("Scale up correctly in case of lost tasks") {
    // Given currentCount = 10, tasksLost = 5, targetCount = 15
    // When the app is scaled
    // Then 5 tasks should be placed onto the launchQueue
  }

  ignore("Kill staged tasks in correct order in case of lost tasks") {
    // Given currentCount = 10 (staged: 7), tasksLost = 5, targetCount = 5
    // When the app is scaled
    // Then the youngest 5 STAGED tasks are killed
  }

  ignore("Kill running tasks in correct order in case of lost tasks") {
    // Given currentCount = 10 (all running), tasksLost = 5, targetCount = 5
    // When the app is scaled
    // Then the youngest running tasks are killed
  }

  ignore("Kill staged and running tasks in correct order in case of lost tasks") {
    // Given currentCount = 10 (3 staging), tasksLost = 5, targetCount = 5
    // When the app is scaled
    // Then the 3 staging tasks plus the 2 youngest running tasks are killed
  }

}
