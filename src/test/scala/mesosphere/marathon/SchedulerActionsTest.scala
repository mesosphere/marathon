package mesosphere.marathon

import akka.testkit.TestProbe
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedTaskInfo
import mesosphere.marathon.core.task.termination.{ TaskKillReason, TaskKillService }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.core.task.tracker.TaskTracker.{ AppTasks, TasksByApp }
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.state.{ AppDefinition, AppRepository, GroupRepository, PathId }
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import org.apache.mesos.Protos.{ TaskID, TaskState, TaskStatus }
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.verifyNoMoreInteractions
import org.scalatest.{ GivenWhenThen, Matchers }
import org.scalatest.concurrent.{ PatienceConfiguration, ScalaFutures }
import org.scalatest.time.{ Millis, Span }

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._

class SchedulerActionsTest
    extends MarathonActorSupport
    with MarathonSpec
    with Matchers
    with Mockito
    with ScalaFutures
    with GivenWhenThen {
  import system.dispatcher

  test("Reset rate limiter if application is stopped") {
    val f = new Fixture
    val app = AppDefinition(id = PathId("/myapp"))

    f.repo.expunge(app.id) returns Future.successful(Seq(true))
    f.taskTracker.appTasks(eq(app.id))(any) returns Future.successful(Iterable.empty[Task])

    f.scheduler.stopApp(app).futureValue(1.second)

    verify(f.queue).purge(app.id)
    verify(f.queue).resetDelay(app)
    verifyNoMoreInteractions(f.queue)
  }

  test("Task reconciliation sends known running and staged tasks and empty list") {
    val f = new Fixture
    val runningTask = MarathonTestHelper.runningTask("task_1")
    val stagedTask = MarathonTestHelper.stagedTask("task_2")

    import MarathonTestHelper.Implicits._
    val stagedTaskWithSlaveId =
      MarathonTestHelper.stagedTask("task_3")
        .withAgentInfo(_.copy(agentId = Some("slave 1")))

    val app = AppDefinition(id = PathId("/myapp"))

    val tasks = Set(runningTask, stagedTask, stagedTaskWithSlaveId)
    f.taskTracker.tasksByApp() returns Future.successful(TasksByApp.of(AppTasks.forTasks(app.id, tasks)))
    f.repo.allPathIds() returns Future.successful(Seq(app.id))

    f.scheduler.reconcileTasks(f.driver).futureValue(5.seconds)

    verify(f.driver).reconcileTasks(Set(
      runningTask,
      stagedTask,
      stagedTaskWithSlaveId
    ).flatMap(_.launched.flatMap(_.status.mesosStatus)).asJava)
    verify(f.driver).reconcileTasks(java.util.Arrays.asList())
  }

  test("Task reconciliation only one empty list, when no tasks are present in Marathon") {
    val f = new Fixture

    f.taskTracker.tasksByApp() returns Future.successful(TasksByApp.empty)
    f.repo.allPathIds() returns Future.successful(Seq())

    f.scheduler.reconcileTasks(f.driver).futureValue

    verify(f.driver, times(1)).reconcileTasks(java.util.Arrays.asList())
  }

  test("Kill orphaned task") {
    val f = new Fixture
    val status = TaskStatus.newBuilder
      .setTaskId(TaskID.newBuilder.setValue("task_1"))
      .setState(TaskState.TASK_RUNNING)
      .build()

    val task = MarathonTestHelper.runningTask("task_1")
    val orphanedTask = MarathonTestHelper.runningTask("orphaned task")

    val app = AppDefinition(id = PathId("/myapp"))
    val tasksOfApp = AppTasks.forTasks(app.id, Iterable(task))
    val orphanedApp = AppDefinition(id = PathId("/orphan"))
    val tasksOfOrphanedApp = AppTasks.forTasks(orphanedApp.id, Iterable(orphanedTask))

    f.taskTracker.tasksByApp() returns Future.successful(TasksByApp.of(tasksOfApp, tasksOfOrphanedApp))
    f.repo.allPathIds() returns Future.successful(Seq(app.id))

    f.scheduler.reconcileTasks(f.driver).futureValue(5.seconds)

    verify(f.killService, times(1)).killTask(orphanedTask, TaskKillReason.Orphaned)
  }

  test("Scale up correctly in case of lost tasks (active queue)") {
    val f = new Fixture

    Given("An active queue and lost tasks")
    val app = MarathonTestHelper.makeBasicApp().copy(instances = 15)
    val queued = QueuedTaskInfo(
      app,
      tasksLeftToLaunch = 1,
      inProgress = true,
      finalTaskCount = 15,
      tasksLost = 5,
      backOffUntil = f.clock.now())
    f.queue.get(app.id) returns Some(queued)
    f.taskTracker.countAppTasksSync(eq(app.id), any) returns (queued.finalTaskCount - queued.tasksLost) // 10

    When("the app is scaled")
    f.scheduler.scale(f.driver, app)

    Then("5 tasks should be placed onto the launchQueue")
    verify(f.queue, times(1)).add(app, 5)
  }

  test("Scale up correctly in case of lost tasks (inactive queue)") {
    val f = new Fixture

    Given("An active queue and lost tasks")
    val app = MarathonTestHelper.makeBasicApp().copy(instances = 15)
    f.queue.get(app.id) returns None
    f.taskTracker.countAppTasksSync(eq(app.id), any) returns 10

    When("the app is scaled")
    f.scheduler.scale(f.driver, app)

    Then("5 tasks should be placed onto the launchQueue")
    verify(f.queue, times(1)).add(app, 5)
  }

  // This scenario is the following:
  // - There's an active queue and Marathon has 10 running + 5 staged tasks
  // - Marathon receives StatusUpdates for 5 previously LOST tasks
  // - A scale is initiated and Marathon realizes there are 5 tasks over capacity
  // => We expect Marathon to kill the 5 staged tasks
  test("Kill staged tasks in correct order in case lost tasks reappear") {
    val f = new Fixture

    Given("an active queue, staged tasks and 5 overCapacity")
    val app = MarathonTestHelper.makeBasicApp().copy(instances = 5)
    val queued = QueuedTaskInfo(
      app,
      tasksLeftToLaunch = 0,
      inProgress = true,
      finalTaskCount = 7,
      tasksLost = 0,
      backOffUntil = f.clock.now())

    def stagedTask(id: String, stagedAt: Long) = MarathonTestHelper.stagedTask(id, stagedAt = stagedAt)

    val staged_2 = stagedTask("staged-2", 2L)
    val staged_3 = stagedTask("staged-3", 3L)
    val tasks = Seq(
      MarathonTestHelper.runningTask(s"running-1"),
      stagedTask("staged-1", 1L),
      MarathonTestHelper.runningTask(s"running-2"),
      staged_3,
      MarathonTestHelper.runningTask(s"running-3"),
      staged_2,
      MarathonTestHelper.runningTask(s"running-4")
    )

    f.queue.get(app.id) returns Some(queued)
    f.taskTracker.countAppTasksSync(eq(app.id), any) returns 7
    f.taskTracker.appTasksSync(app.id) returns tasks
    When("the app is scaled")
    f.scheduler.scale(f.driver, app)

    Then("the queue is purged")
    verify(f.queue, times(1)).purge(app.id)

    And("the youngest STAGED tasks are killed")
    verify(f.killService).killTasks(List(staged_3, staged_2), TaskKillReason.ScalingApp)
    verifyNoMoreInteractions(f.driver)
    verifyNoMoreInteractions(f.killService)
  }

  test("Kill running tasks in correct order in case of lost tasks") {
    val f = new Fixture

    Given("an inactive queue, running tasks and some overCapacity")
    val app = MarathonTestHelper.makeBasicApp().copy(instances = 5)

    def runningTask(id: String, stagedAt: Long) = MarathonTestHelper.runningTask(id, stagedAt = stagedAt)

    val running_6 = runningTask(s"running-6", stagedAt = 6L)
    val running_7 = runningTask(s"running-7", stagedAt = 7L)
    val tasks = Seq(
      runningTask(s"running-3", stagedAt = 3L),
      running_7,
      runningTask(s"running-1", stagedAt = 1L),
      runningTask(s"running-4", stagedAt = 4L),
      runningTask(s"running-5", stagedAt = 5L),
      running_6,
      runningTask(s"running-2", stagedAt = 2L)
    )

    f.queue.get(app.id) returns None
    f.taskTracker.countAppTasksSync(eq(app.id), any) returns 7
    f.taskTracker.appTasksSync(app.id) returns tasks
    When("the app is scaled")
    f.scheduler.scale(f.driver, app)

    Then("the queue is purged")
    verify(f.queue, times(1)).purge(app.id)

    And("the youngest RUNNING tasks are killed")
    verify(f.killService).killTasks(List(running_7, running_6), TaskKillReason.ScalingApp)
    verifyNoMoreInteractions(f.driver)
    verifyNoMoreInteractions(f.killService)
  }

  test("Kill staged and running tasks in correct order in case of lost tasks") {
    val f = new Fixture

    Given("an active queue, running tasks and some overCapacity")
    val app = MarathonTestHelper.makeBasicApp().copy(instances = 3)

    val queued = QueuedTaskInfo(
      app,
      tasksLeftToLaunch = 0,
      inProgress = true,
      finalTaskCount = 5,
      tasksLost = 0,
      backOffUntil = f.clock.now())

    def stagedTask(id: String, stagedAt: Long) = MarathonTestHelper.stagedTask(id, stagedAt = stagedAt)
    def runningTask(id: String, stagedAt: Long) = MarathonTestHelper.runningTask(id, stagedAt = stagedAt)

    val staged_1 = stagedTask("staged-1", 1L)
    val running_4 = runningTask("running-4", stagedAt = 4L)
    val tasks = Seq(
      runningTask("running-3", stagedAt = 3L),
      running_4,
      staged_1,
      runningTask("running-1", stagedAt = 1L),
      runningTask("running-2", stagedAt = 2L)
    )

    f.queue.get(app.id) returns Some(queued)
    f.taskTracker.countAppTasksSync(eq(app.id), any) returns 5
    f.taskTracker.appTasksSync(app.id) returns tasks
    When("the app is scaled")
    f.scheduler.scale(f.driver, app)

    Then("the queue is purged")
    verify(f.queue, times(1)).purge(app.id)

    And("all STAGED tasks plus the youngest RUNNING tasks are killed")
    verify(f.killService).killTasks(List(staged_1, running_4), TaskKillReason.ScalingApp)
    verifyNoMoreInteractions(f.driver)
    verifyNoMoreInteractions(f.killService)
  }

  import scala.language.implicitConversions
  implicit def durationToPatienceConfigTimeout(d: FiniteDuration): PatienceConfiguration.Timeout = {
    PatienceConfiguration.Timeout(Span(d.toMillis, Millis))
  }

  class Fixture {
    val queue = mock[LaunchQueue]
    val repo = mock[AppRepository]
    val taskTracker = mock[TaskTracker]
    val driver = mock[SchedulerDriver]
    val killService = mock[TaskKillService]
    val clock = ConstantClock()

    val scheduler = new SchedulerActions(
      repo,
      mock[GroupRepository],
      mock[HealthCheckManager],
      taskTracker,
      queue,
      system.eventStream,
      TestProbe().ref,
      killService,
      mock[MarathonConf]
    )
  }

}
