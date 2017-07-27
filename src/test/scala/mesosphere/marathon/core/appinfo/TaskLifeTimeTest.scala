package mesosphere.marathon
package core.appinfo

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.{ Instance, LegacyAppInstance, TestTaskBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ PathId, Timestamp, UnreachableStrategy }
import org.joda.time.DateTime

class TaskLifeTimeTest extends UnitTest {
  private[this] val now: Timestamp = Timestamp(new DateTime(2015, 4, 9, 12, 30))
  private[this] val runSpecId = PathId("/test")
  private[this] val agentInfo = AgentInfo(host = "host", agentId = Some("agent"), attributes = Nil)
  private[this] def newTaskId(): Task.Id = {
    Task.Id.forRunSpec(runSpecId)
  }

  private[this] def stagedInstance(): Instance = {
    LegacyAppInstance(TestTaskBuilder.Helper.stagedTask(newTaskId()), agentInfo, UnreachableStrategy.default())
  }

  private[this] def runningInstanceWithLifeTime(lifeTimeSeconds: Double): Instance = {
    LegacyAppInstance(
      TestTaskBuilder.Helper.runningTask(newTaskId(), startedAt = (now.millis - lifeTimeSeconds * 1000.0).round),
      agentInfo,
      UnreachableStrategy.default()
    )
  }

  "TaskLifetime" should {
    "life time for no tasks" in {
      Given("no tasks")
      When("calculating life times")
      val lifeTimes = TaskLifeTime.forSomeTasks(now, Seq.empty)
      Then("we get none")
      lifeTimes should be(None)
    }

    "life time only for tasks which have not yet been running" in {
      Given("not yet running instances")
      val instances = (1 to 3).map(_ => stagedInstance())
      When("calculating life times")
      val lifeTimes = TaskLifeTime.forSomeTasks(now, instances)
      Then("we get none")
      lifeTimes should be(None)
    }

    "life times for task with life times" in {
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

    "life times for task with life times ignore not yet running tasks" in {
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
}
