package mesosphere.marathon
package api.v2.json

import mesosphere.UnitTest
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.core.readiness.{ HttpResponse, ReadinessCheckResult }
import mesosphere.marathon.core.task.Task
import play.api.libs.json.Json

class ReadinessCheckResultFormatTest extends UnitTest {

  import Formats._

  "ReadinessCheckResultFormat" should {
    "ReadinessCheckResult is convertible to JSON" in {
      JsonTestHelper.assertThatJsonOf(Fixture.readinessCheckResult).correspondsToJsonString(Fixture.readinessCheckJson)
    }

    "ReadinessCheckResult is readable from JSON" in {
      val readinessCheckResult = Json.parse(Fixture.readinessCheckJson).as[ReadinessCheckResult]

      readinessCheckResult should equal(Fixture.readinessCheckResult)
    }
  }

  object Fixture {
    val httpResponse = HttpResponse(200, "application/json", "{}")
    val readinessCheckResult = ReadinessCheckResult(
      "readinessCheck",
      Task.Id("/foo/bar"),
      ready = true,
      Some(httpResponse))

    val readinessCheckJson =
      """
        |{
        |  "name": "readinessCheck",
        |  "taskId": "/foo/bar",
        |  "ready": true,
        |  "lastResponse": {
        |    "contentType": "application/json",
        |    "status": 200,
        |    "body": "{}"
        |  }
        |}
      """.stripMargin
  }
}
