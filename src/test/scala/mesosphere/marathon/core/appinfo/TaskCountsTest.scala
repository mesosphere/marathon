package mesosphere.marathon.core.appinfo

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.health.Health
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
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
    Given("one staged task")
    val oneStagedTask = Seq(
      MarathonTestHelper.stagedTask("task1")
    )
    When("getting counts")
    val counts = TaskCounts(appTasks = oneStagedTask, healthStatuses = Map.empty)
    Then("all counts are 0 except staged")
    counts should be(TaskCounts.zero.copy(tasksStaged = 1))
  }

  test("one running task") {
    Given("one running task")
    val oneRunningTask = Seq(
      MarathonTestHelper.runningTask("task1")
    )
    When("getting counts")
    val counts = TaskCounts(appTasks = oneRunningTask, healthStatuses = Map.empty)
    Then("all counts are 0 except running")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1))
  }

  test("one healthy task") {
    Given("one task with alive Health")
    val oneRunningTask = Seq(
      MarathonTestHelper.runningTask("task1")
    )
    When("getting counts")
    val counts = TaskCounts(appTasks = oneRunningTask, healthStatuses = Map(Task.Id("task1") -> aliveHealth))
    Then("all counts are 0 except healthy")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1, tasksHealthy = 1))
  }

  test("one unhealthy task") {
    Given("one task with !alive health")
    val oneRunningTask = Seq(
      MarathonTestHelper.runningTask("task1")
    )
    When("getting counts")
    val counts = TaskCounts(appTasks = oneRunningTask, healthStatuses = Map(Task.Id("task1") -> notAliveHealth))
    Then("all counts are 0 except tasksUnhealthy")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1, tasksUnhealthy = 1))
  }

  test("a task with mixed health is counted as unhealthy") {
    Given("one task with mixed health")
    val oneRunningTask = Seq(
      MarathonTestHelper.runningTask("task1")
    )
    When("getting counts")
    val counts = TaskCounts(appTasks = oneRunningTask, healthStatuses = Map(Task.Id("task1") -> mixedHealth))
    Then("all counts are 0 except tasksUnhealthy")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1, tasksUnhealthy = 1))
  }

  test("one running task with empty health is not counted for health") {
    Given("one running task with empty health info")
    val oneRunningTask = Seq(
      MarathonTestHelper.runningTask("task1")
    )
    When("getting counts")
    val counts = TaskCounts(appTasks = oneRunningTask, healthStatuses = Map(Task.Id("task1") -> noHealths))
    Then("all counts are 0")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1))
  }

  test("one task of each kind") {
    Given("one staged task")
    val oneStagedTask = Seq(
      MarathonTestHelper.stagedTask("task1"),
      MarathonTestHelper.runningTask("task2"),
      MarathonTestHelper.runningTask("task3"),
      MarathonTestHelper.runningTask("task4")
    )
    When("getting counts")
    val counts = TaskCounts(
      appTasks = oneStagedTask,
      healthStatuses = Map(
        Task.Id("task3") -> aliveHealth,
        Task.Id("task4") -> notAliveHealth
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
  val taskWithoutState = Task.LaunchedEphemeral(
    taskId = Task.Id("task1"),
    agentInfo = Task.AgentInfo("some.host", Some("agent-1"), Iterable.empty),
    appVersion = Timestamp(0),
    status = Task.Status(
      stagedAt = Timestamp(1),
      startedAt = None,
      mesosStatus = None
    ),
    hostPorts = Seq.empty
  )

}
