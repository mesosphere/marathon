package mesosphere.marathon.core.appinfo

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.health.Health
import mesosphere.marathon.core.instance.{ Instance, TestTaskBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.{ MarathonSpec, Mockito }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq

class TaskCountsTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {
  test("count no tasks") {
    When("getting counts for no tasks")
    val counts = TaskCounts(appTasks = Seq.empty, healthStatuses = Map.empty)
    Then("all counts are zero")
    counts should be(TaskCounts.zero)
  }

  test("one task without explicit task state is treated as staged task") {
    val f = new Fixture
    Given("one unstaged task")
    val oneTaskWithoutTaskState = f.taskWithoutState
    When("getting counts")
    val counts = TaskCounts(appTasks = Seq(oneTaskWithoutTaskState), healthStatuses = Map.empty)
    Then("the task without taskState is counted as staged")
    counts should be(TaskCounts.zero.copy(tasksStaged = 1))
  }

  test("one staged task") {
    val f = new Fixture
    Given("one staged task")
    val oneStagedTask = Seq(
      TestTaskBuilder.Helper.stagedTaskForApp(f.runSpecId)
    )
    When("getting counts")
    val counts = TaskCounts(appTasks = oneStagedTask, healthStatuses = Map.empty)
    Then("all counts are 0 except staged")
    counts should be(TaskCounts.zero.copy(tasksStaged = 1))
  }

  test("one running task") {
    val f = new Fixture
    Given("one running task")
    val oneRunningTask = Seq(
      TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId)
    )
    When("getting counts")
    val counts = TaskCounts(appTasks = oneRunningTask, healthStatuses = Map.empty)
    Then("all counts are 0 except running")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1))
  }

  test("one healthy task") {
    val f = new Fixture
    Given("one task with alive Health")
    val runningHealthyTask = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId)
    val oneRunningTask = Seq(
      runningHealthyTask
    )
    When("getting counts")
    val counts = TaskCounts(appTasks = oneRunningTask, healthStatuses = Map(runningHealthyTask.taskId -> aliveHealth))
    Then("all counts are 0 except healthy")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1, tasksHealthy = 1))
  }

  test("one unhealthy task") {
    val f = new Fixture
    Given("one task with !alive health")
    val unhealthyTask = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId)
    val oneRunningTask = Seq(
      unhealthyTask
    )
    When("getting counts")
    val counts = TaskCounts(appTasks = oneRunningTask, healthStatuses = Map(unhealthyTask.taskId -> notAliveHealth))
    Then("all counts are 0 except tasksUnhealthy")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1, tasksUnhealthy = 1))
  }

  test("a task with mixed health is counted as unhealthy") {
    val f = new Fixture
    Given("one task with mixed health")
    val task = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId)
    val oneRunningTask = Seq(
      task
    )
    When("getting counts")
    val counts = TaskCounts(appTasks = oneRunningTask, healthStatuses = Map(task.taskId -> mixedHealth))
    Then("all counts are 0 except tasksUnhealthy")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1, tasksUnhealthy = 1))
  }

  test("one running task with empty health is not counted for health") {
    val f = new Fixture
    Given("one running task with empty health info")
    val oneRunningTask = Seq(
      TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId)
    )
    When("getting counts")
    val counts = TaskCounts(appTasks = oneRunningTask, healthStatuses = Map(Task.Id("task1") -> noHealths))
    Then("all counts are 0")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1))
  }

  test("one task of each kind") {
    val f = new Fixture
    Given("one staged task")
    val task1 = TestTaskBuilder.Helper.stagedTaskForApp(f.runSpecId)
    val task2 = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId)
    val task3 = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId)
    val task4 = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId)
    val oneStagedTask = Seq(
      task1, task2, task3, task4
    )
    When("getting counts")
    val counts = TaskCounts(
      appTasks = oneStagedTask,
      healthStatuses = Map(
        task3.taskId -> aliveHealth,
        task4.taskId -> notAliveHealth
      )
    )
    Then("all counts are 0 except staged")
    counts should be(TaskCounts.zero.copy(
      tasksStaged = 1,
      tasksRunning = 3,
      tasksHealthy = 1,
      tasksUnhealthy = 1
    ))
  }

  test("task count difference") {
    val counts1 = TaskCounts(
      tasksStaged = 10,
      tasksRunning = 20,
      tasksHealthy = 30,
      tasksUnhealthy = 40
    )
    val counts2 = TaskCounts(
      tasksStaged = 11,
      tasksRunning = 22,
      tasksHealthy = 33,
      tasksUnhealthy = 44
    )

    (counts2 - counts1) should equal(
      TaskCounts(
        tasksStaged = 1,
        tasksRunning = 2,
        tasksHealthy = 3,
        tasksUnhealthy = 4
      )
    )
  }

  test("task count addition") {
    val counts1 = TaskCounts(
      tasksStaged = 10,
      tasksRunning = 20,
      tasksHealthy = 30,
      tasksUnhealthy = 40
    )
    val counts2 = TaskCounts(
      tasksStaged = 1,
      tasksRunning = 2,
      tasksHealthy = 3,
      tasksUnhealthy = 4
    )

    (counts2 + counts1) should equal(
      TaskCounts(
        tasksStaged = 11,
        tasksRunning = 22,
        tasksHealthy = 33,
        tasksUnhealthy = 44
      )
    )
  }

  private[this] val noHealths = Seq.empty[Health]
  private[this] val aliveHealth = Seq(Health(Task.Id("task1"), lastSuccess = Some(Timestamp(1))))
  require(aliveHealth.forall(_.alive))
  private[this] val notAliveHealth = Seq(Health(Task.Id("task1"), lastFailure = Some(Timestamp(1))))
  require(notAliveHealth.forall(!_.alive))
  private[this] val mixedHealth = aliveHealth ++ notAliveHealth
}

class Fixture {
  val runSpecId = PathId("/test")
  val taskWithoutState = Task.LaunchedEphemeral(
    taskId = Task.Id("task1"),
    agentInfo = Instance.AgentInfo("some.host", Some("agent-1"), Seq.empty),
    runSpecVersion = Timestamp(0),
    status = Task.Status(
      stagedAt = Timestamp(1),
      startedAt = None,
      mesosStatus = None,
      condition = Condition.Running,
      networkInfo = NetworkInfo.empty
    )
  )

}
