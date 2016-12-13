package mesosphere.marathon.core.appinfo

import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.health.Health
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.{ Instance, LegacyAppInstance, TestTaskBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.{ MarathonSpec, Mockito }
import org.scalatest.{ GivenWhenThen, Matchers }

import scala.collection.immutable.Seq

class TaskCountsTest extends MarathonSpec with GivenWhenThen with Mockito with Matchers {
  import mesosphere.marathon.core.appinfo.Fixture.TaskImplicits

  test("count no tasks") {
    When("getting counts for no tasks")
    val counts = TaskCounts(appInstances = Seq.empty, healthStatuses = Map.empty)
    Then("all counts are zero")
    counts should be(TaskCounts.zero)
  }

  test("one task without explicit task state is treated as staged task") {
    val f = new Fixture
    Given("one unstaged task")
    val oneInstanceWithoutTaskState = f.taskWithoutState.toInstance
    When("getting counts")
    val counts = TaskCounts(appInstances = Seq(oneInstanceWithoutTaskState), healthStatuses = Map.empty)
    Then("the task without taskState is counted as staged")
    counts should be(TaskCounts.zero.copy(tasksStaged = 1))
  }

  test("one staged task") {
    val f = new Fixture
    Given("one staged task")
    val oneStagedInstance = Seq(
      TestTaskBuilder.Helper.stagedTaskForApp(f.runSpecId).toInstance
    )
    When("getting counts")
    val counts = TaskCounts(appInstances = oneStagedInstance, healthStatuses = Map.empty)
    Then("all counts are 0 except staged")
    counts should be(TaskCounts.zero.copy(tasksStaged = 1))
  }

  test("one running task") {
    val f = new Fixture
    Given("one running task")
    val oneRunningInstance = Seq(
      TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId).toInstance
    )
    When("getting counts")
    val counts = TaskCounts(appInstances = oneRunningInstance, healthStatuses = Map.empty)
    Then("all counts are 0 except running")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1))
  }

  test("one healthy task") {
    val f = new Fixture
    Given("one task with alive Health")
    val runningHealthyInstance = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId).toInstance
    val oneRunningInstance = Seq(
      runningHealthyInstance
    )
    When("getting counts")
    val counts = TaskCounts(appInstances = oneRunningInstance, healthStatuses = Map(runningHealthyInstance.instanceId -> f.aliveHealth))
    Then("all counts are 0 except healthy")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1, tasksHealthy = 1))
  }

  test("one unhealthy task") {
    val f = new Fixture
    Given("one task with !alive health")
    val unhealthyInstance = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId).toInstance
    val oneRunningInstance = Seq(
      unhealthyInstance
    )
    When("getting counts")
    val counts = TaskCounts(appInstances = oneRunningInstance, healthStatuses = Map(unhealthyInstance.instanceId -> f.notAliveHealth))
    Then("all counts are 0 except tasksUnhealthy")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1, tasksUnhealthy = 1))
  }

  test("a task with mixed health is counted as unhealthy") {
    val f = new Fixture
    Given("one instance with mixed health")
    val instance = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId).toInstance
    val oneRunningInstance = Seq(
      instance
    )
    When("getting counts")
    val counts = TaskCounts(appInstances = oneRunningInstance, healthStatuses = Map(instance.instanceId -> f.mixedHealth))
    Then("all counts are 0 except tasksUnhealthy")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1, tasksUnhealthy = 1))
  }

  test("one running task with empty health is not counted for health") {
    val f = new Fixture
    Given("one running task with empty health info")
    val instance = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId).toInstance
    val oneRunningInstance = Seq(
      instance
    )
    When("getting counts")
    val counts = TaskCounts(appInstances = oneRunningInstance, healthStatuses = Map(instance.instanceId -> f.noHealths))
    Then("all counts are 0")
    counts should be(TaskCounts.zero.copy(tasksRunning = 1))
  }

  test("one task of each kind") {
    val f = new Fixture
    Given("one staged task")
    val instance1 = TestTaskBuilder.Helper.stagedTaskForApp(f.runSpecId).toInstance
    val instance2 = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId).toInstance
    val instance3 = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId).toInstance
    val instance4 = TestTaskBuilder.Helper.runningTaskForApp(f.runSpecId).toInstance
    val oneStagedTask = Seq(
      instance1, instance2, instance3, instance4
    )
    When("getting counts")
    val counts = TaskCounts(
      appInstances = oneStagedTask,
      healthStatuses = Map(
        instance3.instanceId -> f.aliveHealth,
        instance4.instanceId -> f.notAliveHealth
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
}

object Fixture {
  implicit class TaskImplicits(val task: Task) extends AnyVal {
    def toInstance: Instance = LegacyAppInstance(task, AgentInfo(host = "host", agentId = Some("agent"), attributes = Nil))
  }
}

class Fixture {
  val runSpecId = PathId("/test")
  val taskId = Task.Id.forRunSpec(runSpecId)
  val taskWithoutState = Task.LaunchedEphemeral(
    taskId = taskId,
    runSpecVersion = Timestamp(0),
    status = Task.Status(
      stagedAt = Timestamp(1),
      startedAt = None,
      mesosStatus = None,
      condition = Condition.Running,
      networkInfo = NetworkInfo.empty
    )
  )
  val noHealths = Seq.empty[Health]
  val aliveHealth = Seq(Health(taskId.instanceId, lastSuccess = Some(Timestamp(1))))
  require(aliveHealth.forall(_.alive))
  val notAliveHealth = Seq(Health(taskId.instanceId, lastFailure = Some(Timestamp(1))))
  require(notAliveHealth.forall(!_.alive))
  val mixedHealth = aliveHealth ++ notAliveHealth

}
