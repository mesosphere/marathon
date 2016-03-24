package mesosphere.marathon.api.v2.json

import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.core.readiness.{ HttpResponse, ReadinessCheckResult }
import mesosphere.marathon.core.task.Task
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }
import play.api.libs.json.Json

class ReadinessCheckResultFormatTest extends FunSuite with Matchers with GivenWhenThen {
  import Formats._

  test("ReadinessCheckResult is convertible to JSON") {
    JsonTestHelper.assertThatJsonOf(Fixture.readinessCheckResult).correspondsToJsonString(Fixture.readinessCheckJson)
  }

  test("ReadinessCheckResult is readable from JSON") {
    val readinessCheckResult = Json.parse(Fixture.readinessCheckJson).as[ReadinessCheckResult]

    readinessCheckResult should equal(Fixture.readinessCheckResult)
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
