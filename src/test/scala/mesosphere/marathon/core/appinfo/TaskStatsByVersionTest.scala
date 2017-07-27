package mesosphere.marathon
package core.appinfo

import mesosphere.UnitTest
import mesosphere.marathon.core.health.Health
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.{ Instance, LegacyAppInstance, TestTaskBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ PathId, Timestamp, UnreachableStrategy, VersionInfo }
import org.joda.time.DateTime
import play.api.libs.json.Json

import scala.concurrent.duration._

class TaskStatsByVersionTest extends UnitTest {

  "TaskStatsByVersion" should {
    "no tasks" in {
      Given("no tasks")
      When("calculating stats")
      val stats = TaskStatsByVersion(
        now = now,
        versionInfo = versionInfo,
        instances = Seq.empty,
        statuses = Map.empty[Instance.Id, Seq[Health]]
      )
      Then("we get none")
      stats should be(
        TaskStatsByVersion(
          maybeStartedAfterLastScaling = None,
          maybeWithLatestConfig = None,
          maybeWithOutdatedConfig = None,
          maybeTotalSummary = None
        )
      )
    }

    "tasks are correctly split along categories" in {
      Given("various tasks")
      val outdatedInstances = Vector(
        runningInstanceStartedAt(outdatedVersion, 1.seconds),
        runningInstanceStartedAt(outdatedVersion, 2.seconds)
      )
      val afterLastScalingTasks = Vector(
        runningInstanceStartedAt(lastScalingAt, 1.seconds),
        runningInstanceStartedAt(lastScalingAt, 2.seconds)
      )
      val afterLastConfigChangeTasks = Vector(
        runningInstanceStartedAt(lastConfigChangeAt, 1.seconds),
        runningInstanceStartedAt(intermediaryScalingAt, 2.seconds)
      ) ++ afterLastScalingTasks

      val tasks: Seq[Instance] = outdatedInstances ++ afterLastConfigChangeTasks
      val statuses = Map.empty[Instance.Id, Seq[Health]]

      When("calculating stats")
      val stats = TaskStatsByVersion(
        now = now,
        versionInfo = versionInfo,
        instances = tasks,
        statuses = statuses
      )
      Then("we get the correct stats")
      import mesosphere.marathon.api.v2.json.Formats._
      withClue(Json.prettyPrint(Json.obj("stats" -> stats, "tasks" -> tasks))) {
        stats.maybeWithOutdatedConfig should not be empty
        stats.maybeWithLatestConfig should not be empty
        stats.maybeStartedAfterLastScaling should not be empty
        stats.maybeTotalSummary should not be empty

        stats.maybeWithOutdatedConfig should be(TaskStats.forSomeTasks(now, outdatedInstances, statuses))
        stats.maybeWithLatestConfig should be(TaskStats.forSomeTasks(now, afterLastConfigChangeTasks, statuses))
        stats.maybeStartedAfterLastScaling should be(TaskStats.forSomeTasks(now, afterLastScalingTasks, statuses))
        stats.maybeTotalSummary should be(TaskStats.forSomeTasks(now, tasks, statuses))

        stats should be(
          TaskStatsByVersion(
            maybeStartedAfterLastScaling = TaskStats.forSomeTasks(now, afterLastScalingTasks, statuses),
            maybeWithLatestConfig = TaskStats.forSomeTasks(now, afterLastConfigChangeTasks, statuses),
            maybeWithOutdatedConfig = TaskStats.forSomeTasks(now, outdatedInstances, statuses),
            maybeTotalSummary = TaskStats.forSomeTasks(now, tasks, statuses)
          )
        )
      }

    }
  }
  private[this] val now: Timestamp = Timestamp(new DateTime(2015, 4, 9, 12, 30))
  private val lastScalingAt: Timestamp = now - 10.seconds
  private val intermediaryScalingAt: Timestamp = now - 20.seconds
  private val lastConfigChangeAt: Timestamp = now - 100.seconds
  private val outdatedVersion: Timestamp = now - 200.seconds
  private[this] val versionInfo = VersionInfo.FullVersionInfo(
    version = lastScalingAt,
    lastScalingAt = lastScalingAt,
    lastConfigChangeAt = lastConfigChangeAt
  )
  val appId = PathId("/test")
  private[this] def newTaskId(): Task.Id = {
    // TODO(PODS): this relied on incremental taskIds before and might be broken
    Task.Id.forRunSpec(appId)
  }
  private[this] def runningInstanceStartedAt(version: Timestamp, startingDelay: FiniteDuration): Instance = {
    val startedAt = (version + startingDelay).millis
    val agentInfo = AgentInfo(host = "host", agentId = Some("agent"), attributes = Nil)
    LegacyAppInstance(
      TestTaskBuilder.Helper.runningTask(newTaskId(), appVersion = version, startedAt = startedAt),
      agentInfo,
      unreachableStrategy = UnreachableStrategy.default()
    )
  }
}
