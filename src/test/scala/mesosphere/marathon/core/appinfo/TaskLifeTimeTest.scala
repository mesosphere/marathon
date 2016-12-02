package mesosphere.marathon
package core.appinfo

import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.{ Instance, LegacyAppInstance, TestTaskBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.test.{ MarathonSpec, Mockito }
import org.scalatest.{ GivenWhenThen, Matchers }

class TaskLifeTimeTest extends MarathonSpec with Mockito with GivenWhenThen with Matchers {
  private[this] val now: Timestamp = ConstantClock().now()
  private[this] val runSpecId = PathId("/test")
  private[this] val agentInfo = AgentInfo(host = "host", agentId = Some("agent"), attributes = Nil)
  private[this] def newTaskId(): Task.Id = {
    Task.Id.forRunSpec(runSpecId)
  }

  private[this] def stagedInstance(): Instance = {
    LegacyAppInstance(TestTaskBuilder.Helper.stagedTask(newTaskId()), agentInfo)
  }

  private[this] def runningInstanceWithLifeTime(lifeTimeSeconds: Double): Instance = {
    LegacyAppInstance(
      TestTaskBuilder.Helper.runningTask(newTaskId(), startedAt = (now.millis - lifeTimeSeconds * 1000.0).round),
      agentInfo
    )
  }

  test("life time for no tasks") {
    Given("no tasks")
    When("calculating life times")
    val lifeTimes = TaskLifeTime.forSomeTasks(now, Seq.empty)
    Then("we get none")
    lifeTimes should be(None)
  }

  test("life time only for tasks which have not yet been running") {
    Given("not yet running instances")
    val instances = (1 to 3).map(_ => stagedInstance())
    When("calculating life times")
    val lifeTimes = TaskLifeTime.forSomeTasks(now, instances)
    Then("we get none")
    lifeTimes should be(None)
  }

  test("life times for task with life times") {
    Given("three instances with the life times 2s, 4s, 9s")
    val instances = Seq(2.0, 4.0, 9.0).map(runningInstanceWithLifeTime)
    When("calculating life times")
    val lifeTimes = TaskLifeTime.forSomeTasks(now, instances)
    Then("we get the correct stats")
    lifeTimes should be(
      Some(
        TaskLifeTime(
          averageSeconds = 5.0,
          medianSeconds = 4.0
        )
      )
    )
  }

  test("life times for task with life times ignore not yet running tasks") {
    Given("three instances with the life times 2s, 4s, 9s")
    val instances = Seq(2.0, 4.0, 9.0).map(runningInstanceWithLifeTime) ++ Seq(stagedInstance())
    When("calculating life times")
    val lifeTimes = TaskLifeTime.forSomeTasks(now, instances)
    Then("we get the correct stats")
    lifeTimes should be(
      Some(
        TaskLifeTime(
          averageSeconds = 5.0,
          medianSeconds = 4.0
        )
      )
    )
  }
}
