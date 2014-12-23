package mesosphere.marathon.health

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.api.v2.json.Formats

import org.scalatest.Matchers
import play.api.libs.json._

class HealthTest extends MarathonSpec with Formats with Matchers {

  object Fixture {
    val h1 = Health(taskId = "abcd-1234")

    val h2 = Health(
      taskId = "abcd-1234",
      consecutiveFailures = 0,
      firstSuccess = Some(Timestamp(1)),
      lastSuccess = Some(Timestamp(3)),
      lastFailure = Some(Timestamp(2))
    )

    val h3 = Health(
      taskId = "abcd-1234",
      consecutiveFailures = 1,
      firstSuccess = Some(Timestamp(1)),
      lastSuccess = Some(Timestamp(2)),
      lastFailure = Some(Timestamp(3))
    )
  }

  test("ToJson") {
    import Fixture._

    val j1 = Json.toJson(h1)
    j1 \ "taskId" should equal (JsString("abcd-1234"))
    j1 \ "alive" should equal (JsBoolean(false))
    j1 \ "consecutiveFailures" should equal (JsNumber(0))
    j1 \ "firstSuccess" should equal (JsNull)
    j1 \ "lastFailure" should equal (JsNull)
    j1 \ "lastSuccess" should equal (JsNull)

    val j2 = Json.toJson(h2)
    j2 \ "taskId" should equal (JsString("abcd-1234"))
    j2 \ "alive" should equal (JsBoolean(true))
    j2 \ "consecutiveFailures" should equal (JsNumber(0))
    j2 \ "firstSuccess" should equal (JsString("1970-01-01T00:00:00.001Z"))
    j2 \ "lastFailure" should equal (JsString("1970-01-01T00:00:00.002Z"))
    j2 \ "lastSuccess" should equal (JsString("1970-01-01T00:00:00.003Z"))

    val j3 = Json.toJson(h3)
    j3 \ "taskId" should equal (JsString("abcd-1234"))
    j3 \ "alive" should equal (JsBoolean(false))
    j3 \ "consecutiveFailures" should equal (JsNumber(1))
    j3 \ "firstSuccess" should equal (JsString("1970-01-01T00:00:00.001Z"))
    j3 \ "lastFailure" should equal (JsString("1970-01-01T00:00:00.003Z"))
    j3 \ "lastSuccess" should equal (JsString("1970-01-01T00:00:00.002Z"))

  }

}
