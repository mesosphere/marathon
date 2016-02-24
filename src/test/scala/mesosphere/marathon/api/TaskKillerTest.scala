package mesosphere.marathon.api

import mesosphere.marathon._
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state.{ AppDefinition, Group, GroupManager, PathId, Timestamp }
import mesosphere.marathon.upgrade.DeploymentPlan
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfterAll, Matchers }

import scala.concurrent.Future

class TaskKillerTest extends MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with MockitoSugar
    with ScalaFutures {

  var tracker: TaskTracker = _
  var service: MarathonSchedulerService = _
  var groupManager: GroupManager = _
  var taskKiller: TaskKiller = _

  before {
    service = mock[MarathonSchedulerService]
    tracker = mock[TaskTracker]
    groupManager = mock[GroupManager]
    taskKiller = new TaskKiller(tracker, groupManager, service)
  }

  test("AppNotFound") {
    val appId = PathId("invalid")
    when(tracker.appTasksLaunchedSync(appId)).thenReturn(Iterable.empty)

    val result = taskKiller.kill(appId, (tasks) => Set.empty[Task])
    result.failed.futureValue shouldEqual UnknownAppException(appId)
  }

  test("AppNotFound with scaling") {
    val appId = PathId("invalid")
    when(tracker.hasAppTasksSync(appId)).thenReturn(false)

    val result = taskKiller.killAndScale(appId, (tasks) => Set.empty[Task], force = true)
    result.failed.futureValue shouldEqual UnknownAppException(appId)
  }

  test("KillRequested with scaling") {
    val appId = PathId(List("app"))
    val now = Timestamp.now()
    val task1 = MarathonTestHelper.runningTaskForApp(appId)
    val task2 = MarathonTestHelper.runningTaskForApp(appId)
    val tasksToKill = Set(task1, task2)
    val originalAppDefinition = AppDefinition(appId, instances = 23)

    when(tracker.hasAppTasksSync(appId)).thenReturn(true)
    when(groupManager.group(appId.parent)).thenReturn(Future.successful(Some(Group.emptyWithId(appId.parent))))

    val groupUpdateCaptor = ArgumentCaptor.forClass(classOf[(Group) => Group])
    val forceCaptor = ArgumentCaptor.forClass(classOf[Boolean])
    val toKillCaptor = ArgumentCaptor.forClass(classOf[Map[PathId, Iterable[Task]]])
    val expectedDeploymentPlan = DeploymentPlan.empty
    when(groupManager.update(
      any[PathId],
      groupUpdateCaptor.capture(),
      any[Timestamp],
      forceCaptor.capture(),
      toKillCaptor.capture())
    ).thenReturn(Future.successful(expectedDeploymentPlan))

    val result = taskKiller.killAndScale(appId, (tasks) => tasksToKill, force = true)
    result.futureValue shouldEqual expectedDeploymentPlan
    forceCaptor.getValue shouldEqual true
    toKillCaptor.getValue shouldEqual Map(appId -> tasksToKill)
  }

  test("KillRequested without scaling") {
    val appId = PathId(List("my", "app"))
    val tasksToKill = Set(MarathonTestHelper.runningTaskForApp(appId))
    when(tracker.appTasksLaunchedSync(appId)).thenReturn(tasksToKill)

    val result = taskKiller.kill(appId, { tasks =>
      tasks should equal(tasksToKill)
      tasksToKill
    })
    result.futureValue shouldEqual tasksToKill
    verify(service, times(1)).killTasks(appId, tasksToKill)
    verifyNoMoreInteractions(groupManager)
  }

  test("Kill and scale w/o force should fail if there is a deployment") {
    val appId = PathId(List("my", "app"))
    val now = Timestamp.now()
    val task1 = MarathonTestHelper.runningTaskForApp(appId)
    val task2 = MarathonTestHelper.runningTaskForApp(appId)
    val tasksToKill = Set(task1, task2)

    when(tracker.hasAppTasksSync(appId)).thenReturn(true)
    when(groupManager.group(appId.parent)).thenReturn(Future.successful(Some(Group.emptyWithId(appId.parent))))
    val groupUpdateCaptor = ArgumentCaptor.forClass(classOf[(Group) => Group])
    val forceCaptor = ArgumentCaptor.forClass(classOf[Boolean])
    when(groupManager.update(
      any[PathId],
      groupUpdateCaptor.capture(),
      any[Timestamp],
      forceCaptor.capture(),
      any[Map[PathId, Iterable[Task]]]
    )).thenReturn(Future.failed(AppLockedException()))

    val result = taskKiller.killAndScale(appId, (tasks) => tasksToKill, force = false)
    forceCaptor.getValue shouldEqual false
    result.failed.futureValue shouldEqual AppLockedException()
  }
}
