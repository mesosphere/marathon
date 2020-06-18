package mesosphere.marathon
package api.v2.json

import mesosphere.UnitTest
import mesosphere.marathon.api.JsonTestHelper
import play.api.libs.json.Json

class TaskStatsByVersionFormatTest extends UnitTest {

  private[this] val emptyStats = raml.TaskStatsByVersion(
    startedAfterLastScaling = None,
    withLatestConfig = None,
    withOutdatedConfig = None,
    totalSummary = None
  )

  private[this] val fullTaskStats = raml.TaskStats(
    counts = raml.TaskCounts(
      staged = 1,
      running = 2,
      healthy = 3,
      unhealthy = 4
    ),
    lifeTime = Some(
      raml.TaskLifeTime(
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
      val json = Json.toJson(raml.Stats(fullTaskStats))
      Then("we get the correct json")
      JsonTestHelper
        .assertThatJsonOf(json)
        .correspondsToJsonOf(
          Json.obj(
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
          )
        )
    }

    "fullTaskStats (not by version) without lifeTimes get rendered correctly" in {
      When("serializing to JSON")
      val json = Json.toJson(raml.Stats(fullTaskStats.copy(lifeTime = None)))
      Then("we get the correct json")
      JsonTestHelper
        .assertThatJsonOf(json)
        .correspondsToJsonOf(
          Json.obj(
            "stats" -> Json.obj(
              "counts" -> Json.obj(
                "staged" -> 1,
                "running" -> 2,
                "healthy" -> 3,
                "unhealthy" -> 4
              )
            )
          )
        )
    }

    "full task stats by version get rendered correctly" in {
      // we just vary the task running count to see that the different instances get rendered to the correct output
      val fullStats = raml.TaskStatsByVersion(
        startedAfterLastScaling = Some(raml.Stats(fullTaskStats.copy(fullTaskStats.counts.copy(running = 100)))),
        withLatestConfig = Some(raml.Stats(fullTaskStats.copy(fullTaskStats.counts.copy(running = 200)))),
        withOutdatedConfig = Some(raml.Stats(fullTaskStats.copy(fullTaskStats.counts.copy(running = 300)))),
        totalSummary = Some(raml.Stats(fullTaskStats.copy(fullTaskStats.counts.copy(running = 500))))
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
