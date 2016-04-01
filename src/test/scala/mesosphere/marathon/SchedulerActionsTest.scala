package mesosphere.marathon

import akka.testkit.TestProbe
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.TaskTracker.{ AppTasks, TasksByApp }
import mesosphere.marathon.health.HealthCheckManager
import mesosphere.marathon.state.{ AppDefinition, AppRepository, GroupRepository, PathId }
import mesosphere.marathon.test.{ Mockito, MarathonActorSupport }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ times, verify, verifyNoMoreInteractions, when }
import org.scalatest.Matchers
import org.scalatest.mock.MockitoSugar

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class SchedulerActionsTest extends MarathonActorSupport with MarathonSpec with Matchers with Mockito {
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
    when(taskTracker.appTasks(eq(app.id))(any)).thenReturn(Future.successful(Iterable.empty[Task]))

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

    val runningTask = MarathonTestHelper.runningTask("task_1")

    val stagedTask = MarathonTestHelper.stagedTask("task_2")

    import MarathonTestHelper.Implicits._
    val stagedTaskWithSlaveId =
      MarathonTestHelper.stagedTask("task_3")
        .withAgentInfo(_.copy(agentId = Some("slave 1")))

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
    when(taskTracker.tasksByApp()).thenReturn(Future.successful(TasksByApp.of(AppTasks.forTasks(app.id, tasks))))
    when(repo.allPathIds()).thenReturn(Future.successful(Seq(app.id)))

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver).reconcileTasks(Set(
      runningTask,
      stagedTask,
      stagedTaskWithSlaveId
    ).flatMap(_.launched.flatMap(_.status.mesosStatus)).asJava)
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

    val task = MarathonTestHelper.runningTask("task_1")

    val orphanedTask = MarathonTestHelper.runningTask("orphaned task")

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
    val tasksOfApp = AppTasks.forTasks(app.id, Iterable(task))
    val orphanedApp = AppDefinition(id = PathId("/orphan"))
    val tasksOfOrphanedApp = AppTasks.forTasks(orphanedApp.id, Iterable(orphanedTask))

    when(taskTracker.tasksByApp()).thenReturn(Future.successful(TasksByApp.of(tasksOfApp, tasksOfOrphanedApp)))
    when(repo.allPathIds()).thenReturn(Future.successful(Seq(app.id)))

    Await.result(scheduler.reconcileTasks(driver), 5.seconds)

    verify(driver, times(1)).killTask(orphanedTask.launchedMesosId.get)
  }
}
