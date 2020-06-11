package mesosphere.marathon
package core.appinfo

import java.time.{OffsetDateTime, ZoneOffset}

import mesosphere.UnitTest
import mesosphere.marathon.core.appinfo.impl.TaskForStatistics
import mesosphere.marathon.core.health.Health
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder, TestTaskBuilder}
import mesosphere.marathon.state.{AbsolutePathId, Timestamp, UnreachableStrategy, VersionInfo}
import play.api.libs.json.Json

import scala.concurrent.duration._

class TaskStatsByVersionTest extends UnitTest {

  private[this] def taskStatsForSomeTasks(
      now: Timestamp,
      instances: Seq[Instance],
      statuses: Map[Instance.Id, Seq[Health]]
  ): Option[raml.TaskStats] = {
    TaskStats.forSomeTasks(TaskForStatistics.forInstances(now, instances, statuses))
  }

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
        raml.TaskStatsByVersion(
          startedAfterLastScaling = None,
          withLatestConfig = None,
          withOutdatedConfig = None,
          totalSummary = None
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

      val instances: Seq[Instance] = outdatedInstances ++ afterLastConfigChangeTasks
      val statuses = Map.empty[Instance.Id, Seq[Health]]

      When("calculating stats")
      val stats = TaskStatsByVersion(
        now = now,
        versionInfo = versionInfo,
        instances = instances,
        statuses = statuses
      )
      Then("we get the correct stats")
      withClue(Json.prettyPrint(Json.obj("stats" -> stats.toString, "tasks" -> instances.map(state.Instance.fromCoreInstance)))) {
        stats.withOutdatedConfig.value.stats should be(taskStatsForSomeTasks(now, outdatedInstances, statuses).value)
        stats.withLatestConfig.value.stats should be(taskStatsForSomeTasks(now, afterLastConfigChangeTasks, statuses).value)
        stats.startedAfterLastScaling.value.stats should be(taskStatsForSomeTasks(now, afterLastScalingTasks, statuses).value)
        stats.totalSummary.value.stats should be(taskStatsForSomeTasks(now, instances, statuses).value)

        stats should be(
          raml.TaskStatsByVersion(
            startedAfterLastScaling = taskStatsForSomeTasks(now, afterLastScalingTasks, statuses).map(raml.Stats(_)),
            withLatestConfig = taskStatsForSomeTasks(now, afterLastConfigChangeTasks, statuses).map(raml.Stats(_)),
            withOutdatedConfig = taskStatsForSomeTasks(now, outdatedInstances, statuses).map(raml.Stats(_)),
            totalSummary = taskStatsForSomeTasks(now, instances, statuses).map(raml.Stats(_))
          )
        )
      }

    }
  }
  private[this] val now: Timestamp = Timestamp(OffsetDateTime.of(2015, 4, 9, 12, 30, 0, 0, ZoneOffset.UTC))
  private val lastScalingAt: Timestamp = now - 10.seconds
  private val intermediaryScalingAt: Timestamp = now - 20.seconds
  private val lastConfigChangeAt: Timestamp = now - 100.seconds
  private val outdatedVersion: Timestamp = now - 200.seconds
  private[this] val versionInfo = VersionInfo.FullVersionInfo(
    version = lastScalingAt,
    lastScalingAt = lastScalingAt,
    lastConfigChangeAt = lastConfigChangeAt
  )
  val appId = AbsolutePathId("/test")
  private[this] def newInstanceId(): Instance.Id = Instance.Id.forRunSpec(appId)

  private[this] def runningInstanceStartedAt(version: Timestamp, startingDelay: FiniteDuration): Instance = {
    val startedAt = (version + startingDelay).millis
    val agentInfo = AgentInfo(host = "host", agentId = Some("agent"), region = None, zone = None, attributes = Nil)
    TestInstanceBuilder.fromTask(
      TestTaskBuilder.Helper.runningTask(newInstanceId(), appVersion = version, startedAt = startedAt),
      agentInfo,
      unreachableStrategy = UnreachableStrategy.default()
    )
  }
}
