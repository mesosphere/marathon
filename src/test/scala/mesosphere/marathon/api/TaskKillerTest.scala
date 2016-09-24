package mesosphere.marathon
package api

import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.task.tracker.{ TaskStateOpProcessor, TaskTracker }
import mesosphere.marathon.core.task.{ Task, TaskStateChange, TaskStateOp }
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, Timestamp }
import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper }
import mesosphere.marathon.upgrade.DeploymentPlan
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, GivenWhenThen, Matchers }

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class TaskKillerTest extends MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with GivenWhenThen
    with MockitoSugar
    with ScalaFutures {

  val auth: TestAuthFixture = new TestAuthFixture
  implicit val identity = auth.identity

  //regression for #3251
  test("No tasks to kill should return with an empty array") {
    val f = new Fixture
    val appId = PathId("invalid")
    when(f.tracker.appTasks(appId)).thenReturn(Future.successful(Seq.empty))
    when(f.groupManager.app(appId)).thenReturn(Future.successful(Some(AppDefinition(appId))))

    val result = f.taskKiller.kill(appId, (tasks) => Seq.empty[Task]).futureValue
    result.isEmpty shouldEqual true
  }

  test("AppNotFound") {
    val f = new Fixture
    val appId = PathId("invalid")
    when(f.tracker.appTasks(appId)).thenReturn(Future.successful(Seq.empty))
    when(f.groupManager.app(appId)).thenReturn(Future.successful(None))

    val result = f.taskKiller.kill(appId, (tasks) => Seq.empty[Task])
    result.failed.futureValue shouldEqual UnknownAppException(appId)
  }

  test("AppNotFound with scaling") {
    val f = new Fixture
    val appId = PathId("invalid")
    when(f.tracker.hasAppTasksSync(appId)).thenReturn(false)

    val result = f.taskKiller.killAndScale(appId, (tasks) => Seq.empty[Task], force = true)
    result.failed.futureValue shouldEqual UnknownAppException(appId)
  }

  test("KillRequested with scaling") {
    val f = new Fixture
    val appId = PathId(List("app"))
    val task1 = MarathonTestHelper.runningTaskForApp(appId)
    val task2 = MarathonTestHelper.runningTaskForApp(appId)
    val tasksToKill = Seq(task1, task2)

    when(f.tracker.hasAppTasksSync(appId)).thenReturn(true)
    when(f.groupManager.group(appId.parent)).thenReturn(Future.successful(Some(Group.emptyWithId(appId.parent))))

    val groupUpdateCaptor = ArgumentCaptor.forClass(classOf[(Group) => Group])
    val forceCaptor = ArgumentCaptor.forClass(classOf[Boolean])
    val toKillCaptor = ArgumentCaptor.forClass(classOf[Map[PathId, Seq[Task]]])
    val expectedDeploymentPlan = DeploymentPlan.empty
    when(f.groupManager.update(
      any[PathId],
      groupUpdateCaptor.capture(),
      any[Timestamp],
      forceCaptor.capture(),
      toKillCaptor.capture())
    ).thenReturn(Future.successful(expectedDeploymentPlan))

    val result = f.taskKiller.killAndScale(appId, (tasks) => tasksToKill, force = true)
    result.futureValue shouldEqual expectedDeploymentPlan
    forceCaptor.getValue shouldEqual true
    toKillCaptor.getValue shouldEqual Map(appId -> tasksToKill)
  }

  test("KillRequested without scaling") {
    val f = new Fixture
    val appId = PathId(List("my", "app"))
    val tasksToKill = Seq(MarathonTestHelper.runningTaskForApp(appId))
    when(f.groupManager.app(appId)).thenReturn(Future.successful(Some(AppDefinition(appId))))
    when(f.tracker.appTasks(appId)).thenReturn(Future.successful(tasksToKill))
    when(f.service.killTasks(appId, tasksToKill))
      .thenReturn(Future.successful(MarathonSchedulerActor.TasksKilled(appId, tasksToKill)))

    val result = f.taskKiller.kill(appId, { tasks =>
      tasks should equal(tasksToKill)
      tasksToKill
    })

    result.futureValue shouldEqual tasksToKill
    verify(f.service, times(1)).killTasks(appId, tasksToKill)
  }

  test("Fail when one task kill fails") {
    Given("An app with several tasks")
    val f = new Fixture
    val appId = PathId(List("my", "app"))
    val tasksToKill = Seq(
      MarathonTestHelper.runningTaskForApp(appId),
      MarathonTestHelper.runningTaskForApp(appId)
    )
    when(f.groupManager.app(appId)).thenReturn(Future.successful(Some(AppDefinition(appId))))
    when(f.tracker.appTasks(appId)).thenReturn(Future.successful(tasksToKill))
    when(f.service.killTasks(appId, tasksToKill)).thenReturn(Future.failed(AppLockedException()))

    When("TaskKiller kills all tasks")
    val result = f.taskKiller.kill(appId, { tasks =>
      tasks should equal(tasksToKill)
      tasksToKill
    })

    Then("The kill should fail.")
    result.failed.futureValue shouldEqual AppLockedException()
    verify(f.service, times(1)).killTasks(appId, tasksToKill)
  }

  test("Kill and scale w/o force should fail if there is a deployment") {
    val f = new Fixture
    val appId = PathId(List("my", "app"))
    val task1 = MarathonTestHelper.runningTaskForApp(appId)
    val task2 = MarathonTestHelper.runningTaskForApp(appId)
    val tasksToKill = Seq(task1, task2)

    when(f.tracker.hasAppTasksSync(appId)).thenReturn(true)
    when(f.groupManager.group(appId.parent)).thenReturn(Future.successful(Some(Group.emptyWithId(appId.parent))))
    val groupUpdateCaptor = ArgumentCaptor.forClass(classOf[(Group) => Group])
    val forceCaptor = ArgumentCaptor.forClass(classOf[Boolean])
    when(f.groupManager.update(
      any[PathId],
      groupUpdateCaptor.capture(),
      any[Timestamp],
      forceCaptor.capture(),
      any[Map[PathId, Seq[Task]]]
    )).thenReturn(Future.failed(AppLockedException()))

    val result = f.taskKiller.killAndScale(appId, (tasks) => tasksToKill, force = false)
    forceCaptor.getValue shouldEqual false
    result.failed.futureValue shouldEqual AppLockedException()
  }

  test("kill with wipe will kill running and expunge all") {
    val f = new Fixture
    val appId = PathId(List("my", "app"))
    val runningTask = MarathonTestHelper.runningTaskForApp(appId)
    val reservedTask = MarathonTestHelper.residentReservedTask(appId)
    val tasksToKill = Seq(runningTask, reservedTask)
    val launchedTasks = Seq(runningTask)
    val stateOp1 = TaskStateOp.ForceExpunge(runningTask.taskId)
    val stateOp2 = TaskStateOp.ForceExpunge(reservedTask.taskId)

    when(f.groupManager.app(appId)).thenReturn(Future.successful(Some(AppDefinition(appId))))
    when(f.tracker.appTasks(appId)).thenReturn(Future.successful(tasksToKill))
    when(f.stateOpProcessor.process(stateOp1)).thenReturn(Future.successful(TaskStateChange.Expunge(runningTask)))
    when(f.stateOpProcessor.process(stateOp2)).thenReturn(Future.successful(TaskStateChange.Expunge(reservedTask)))
    when(f.service.killTasks(appId, launchedTasks))
      .thenReturn(Future.successful(MarathonSchedulerActor.TasksKilled(appId, launchedTasks)))

    val result = f.taskKiller.kill(appId, { tasks =>
      tasks should equal(tasksToKill)
      tasksToKill
    }, wipe = true)
    result.futureValue shouldEqual tasksToKill
    // only task1 is killed
    verify(f.service, times(1)).killTasks(appId, launchedTasks)
    // both tasks are expunged from the repo
    verify(f.stateOpProcessor).process(TaskStateOp.ForceExpunge(runningTask.taskId))
    verify(f.stateOpProcessor).process(TaskStateOp.ForceExpunge(reservedTask.taskId))
  }

  class Fixture {
    val tracker: TaskTracker = mock[TaskTracker]
    val stateOpProcessor: TaskStateOpProcessor = mock[TaskStateOpProcessor]
    val service: MarathonSchedulerService = mock[MarathonSchedulerService]
    val groupManager: GroupManager = mock[GroupManager]

    val config: MarathonConf = mock[MarathonConf]
    when(config.zkTimeoutDuration).thenReturn(1.second)

    val taskKiller: TaskKiller = new TaskKiller(tracker, stateOpProcessor, groupManager, service, config, auth.auth, auth.auth)
  }

}
