package mesosphere.marathon.core.appinfo

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonTestHelper, MarathonSpec, Protos }
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.state.Timestamp
import org.scalatest.{ GivenWhenThen, Matchers }

class TaskLifeTimeTest extends MarathonSpec with Mockito with GivenWhenThen with Matchers {
  private[this] val now: Timestamp = ConstantClock().now()
  private[this] var taskIdCounter = 0
  private[this] def newTaskId(): String = {
    taskIdCounter += 1
    s"task$taskIdCounter"
  }

  private[this] def stagedTask(): Task = {
    MarathonTestHelper.stagedTask(newTaskId())
  }

  private[this] def runningTaskWithLifeTime(lifeTimeSeconds: Double): Task = {
    MarathonTestHelper.runningTask(newTaskId(), startedAt = (now.toDateTime.getMillis - lifeTimeSeconds * 1000.0).round)
  }

  test("life time for no tasks") {
    Given("no tasks")
    When("calculating life times")
    val lifeTimes = TaskLifeTime.forSomeTasks(now, Seq.empty)
    Then("we get none")
    lifeTimes should be(None)
  }

  test("life time only for tasks which have not yet been running") {
    Given("not yet running tasks")
    val tasks = (1 to 3).map(_ => stagedTask())
    When("calculating life times")
    val lifeTimes = TaskLifeTime.forSomeTasks(now, tasks)
    Then("we get none")
    lifeTimes should be(None)
  }

  test("life times for task with life times") {
    Given("three tasks with the life times 2s, 4s, 9s")
    val tasks = Seq(2.0, 4.0, 9.0).map(runningTaskWithLifeTime)
    When("calculating life times")
    val lifeTimes = TaskLifeTime.forSomeTasks(now, tasks)
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
    Given("three tasks with the life times 2s, 4s, 9s")
    val tasks = Seq(2.0, 4.0, 9.0).map(runningTaskWithLifeTime) ++ Seq(stagedTask())
    When("calculating life times")
    val lifeTimes = TaskLifeTime.forSomeTasks(now, tasks)
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
