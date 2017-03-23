package mesosphere.marathon
package api.v2.json

import mesosphere.UnitTest
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.core.appinfo.{ TaskCounts, TaskLifeTime, TaskStats, TaskStatsByVersion }
import play.api.libs.json.Json

class TaskStatsByVersionFormatTest extends UnitTest {
  import Formats._

  private[this] val emptyStats = TaskStatsByVersion(
    maybeStartedAfterLastScaling = None,
    maybeWithLatestConfig = None,
    maybeWithOutdatedConfig = None,
    maybeTotalSummary = None
  )

  private[this] val fullTaskStats = TaskStats(
    counts = TaskCounts(
      tasksStaged = 1,
      tasksRunning = 2,
      tasksHealthy = 3,
      tasksUnhealthy = 4
    ),
    maybeLifeTime = Some(
      TaskLifeTime(
        averageSeconds = 20.0,
        medianSeconds = 10.0
      )
    )
  )

  "TaskStatsByVersion" should {
    "empty stats get rendered correctly" in {
      When("serializing to JSON")
      val json = Json.toJson(emptyStats)
      Then("we get an empty object")
      JsonTestHelper.assertThatJsonOf(json).correspondsToJsonOf(Json.obj())
    }

    "fullTaskStats (not by version) get rendered correctly" in {
      When("serializing to JSON")
      val json = Json.toJson(fullTaskStats)
      Then("we get the correct json")
      JsonTestHelper.assertThatJsonOf(json).correspondsToJsonOf(Json.obj(
        "stats" -> Json.obj(
          "counts" -> Json.obj(
            "staged" -> 1,
            "running" -> 2,
            "healthy" -> 3,
            "unhealthy" -> 4
          ),
          "lifeTime" -> Json.obj(
            "averageSeconds" -> 20.0,
            "medianSeconds" -> 10.0
          )
        )
      ))
    }

    "fullTaskStats (not by version) without lifeTimes get rendered correctly" in {
      When("serializing to JSON")
      val json = Json.toJson(fullTaskStats.copy(maybeLifeTime = None))
      Then("we get the correct json")
      JsonTestHelper.assertThatJsonOf(json).correspondsToJsonOf(Json.obj(
        "stats" -> Json.obj(
          "counts" -> Json.obj(
            "staged" -> 1,
            "running" -> 2,
            "healthy" -> 3,
            "unhealthy" -> 4
          )
        )
      ))
    }

    "full task stats by version get rendered correctly" in {
      // we just vary the task running count to see that the different instances get rendered to the correct output
      val fullStats = TaskStatsByVersion(
        maybeStartedAfterLastScaling = Some(fullTaskStats.copy(fullTaskStats.counts.copy(tasksRunning = 100))),
        maybeWithLatestConfig = Some(fullTaskStats.copy(fullTaskStats.counts.copy(tasksRunning = 200))),
        maybeWithOutdatedConfig = Some(fullTaskStats.copy(fullTaskStats.counts.copy(tasksRunning = 300))),
        maybeTotalSummary = Some(fullTaskStats.copy(fullTaskStats.counts.copy(tasksRunning = 500)))
      )

      When("serializing to JSON")
      val json = Json.toJson(fullStats)
      Then("the stats get rendered into the correct sub fields")
      withClue(Json.prettyPrint(json)) {
        (json \ "startedAfterLastScaling" \ "stats" \ "counts" \ "running").as[Int] should be(100)
        (json \ "withLatestConfig" \ "stats" \ "counts" \ "running").as[Int] should be(200)
        (json \ "withOutdatedConfig" \ "stats" \ "counts" \ "running").as[Int] should be(300)
        (json \ "totalSummary" \ "stats" \ "counts" \ "running").as[Int] should be(500)
      }
    }
  }
}
