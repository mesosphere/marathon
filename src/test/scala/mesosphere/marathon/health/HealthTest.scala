package mesosphere.marathon.health

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.api.v2.json.Formats

import org.scalatest.Matchers
import play.api.libs.json._

class HealthTest extends MarathonSpec with Formats with Matchers {

  object Fixture {
    val h1 = Health(taskId = Task.Id("abcd-1234"))

    val h2 = Health(
      taskId = Task.Id("abcd-1234"),
      consecutiveFailures = 0,
      firstSuccess = Some(Timestamp(1)),
      lastSuccess = Some(Timestamp(3)),
      lastFailure = Some(Timestamp(2))
    )

    val h3 = Health(
      taskId = Task.Id("abcd-1234"),
      consecutiveFailures = 1,
      firstSuccess = Some(Timestamp(1)),
      lastSuccess = Some(Timestamp(2)),
      lastFailure = Some(Timestamp(3))
    )
  }

  test("ToJson") {
    import Fixture._

    val j1 = Json.toJson(h1)
    (j1 \ "taskId").as[String] should equal ("abcd-1234")
    (j1 \ "alive").as[Boolean] should equal (false)
    (j1 \ "consecutiveFailures").as[Int] should equal (0)
    (j1 \ "firstSuccess").asOpt[String] should equal (None)
    (j1 \ "lastFailure").asOpt[String] should equal (None)
    (j1 \ "lastSuccess").asOpt[String] should equal (None)

    val j2 = Json.toJson(h2)
    (j2 \ "taskId").as[String] should equal ("abcd-1234")
    (j2 \ "alive").as[Boolean] should equal (true)
    (j2 \ "consecutiveFailures").as[Int] should equal (0)
    (j2 \ "firstSuccess").as[String] should equal ("1970-01-01T00:00:00.001Z")
    (j2 \ "lastFailure").as[String] should equal ("1970-01-01T00:00:00.002Z")
    (j2 \ "lastSuccess").as[String] should equal ("1970-01-01T00:00:00.003Z")

    val j3 = Json.toJson(h3)
    (j3 \ "taskId").as[String] should equal ("abcd-1234")
    (j3 \ "alive").as[Boolean] should equal (false)
    (j3 \ "consecutiveFailures").as[Int] should equal (1)
    (j3 \ "firstSuccess").as[String] should equal ("1970-01-01T00:00:00.001Z")
    (j3 \ "lastFailure").as[String] should equal ("1970-01-01T00:00:00.003Z")
    (j3 \ "lastSuccess").as[String] should equal ("1970-01-01T00:00:00.002Z")
  }

}
