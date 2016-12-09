package mesosphere.marathon.core.health

import mesosphere.UnitTest
import mesosphere.marathon.api.v2.json.Formats
import mesosphere.marathon.core.event.HealthStatusChanged
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ PathId, Timestamp }
import play.api.libs.json._

class HealthTest extends UnitTest with Formats {

  "Health" should {
    val f = new Fixture
    import f._
    "serialize correctly" in {
      val j1 = Json.toJson(h1)
      (j1 \ "instanceId").as[String] should equal (instanceId.idString)
      (j1 \ "alive").as[Boolean] should equal (false)
      (j1 \ "consecutiveFailures").as[Int] should equal (0)
      (j1 \ "firstSuccess").asOpt[String] should equal (None)
      (j1 \ "lastFailure").asOpt[String] should equal (None)
      (j1 \ "lastSuccess").asOpt[String] should equal (None)

      val j2 = Json.toJson(h2)
      (j2 \ "instanceId").as[String] should equal (instanceId.idString)
      (j2 \ "alive").as[Boolean] should equal (true)
      (j2 \ "consecutiveFailures").as[Int] should equal (0)
      (j2 \ "firstSuccess").as[String] should equal ("1970-01-01T00:00:00.001Z")
      (j2 \ "lastFailure").as[String] should equal ("1970-01-01T00:00:00.002Z")
      (j2 \ "lastSuccess").as[String] should equal ("1970-01-01T00:00:00.003Z")

      val j3 = Json.toJson(h3)
      (j3 \ "instanceId").as[String] should equal (instanceId.idString)
      (j3 \ "alive").as[Boolean] should equal (false)
      (j3 \ "consecutiveFailures").as[Int] should equal (1)
      (j3 \ "firstSuccess").as[String] should equal ("1970-01-01T00:00:00.001Z")
      (j3 \ "lastFailure").as[String] should equal ("1970-01-01T00:00:00.003Z")
      (j3 \ "lastSuccess").as[String] should equal ("1970-01-01T00:00:00.002Z")
    }
  }

  "HealthStatusChangedEvent" should {
    "serialize correctly" in {
      val f = new Fixture
      val event = HealthStatusChanged(
        appId = f.appId,
        instanceId = f.instanceId,
        version = f.version,
        alive = true,
        timestamp = f.now.toString
      )

      val json = Json.toJson(event)
      println(json.toString())
      (json \ "appId").as[String] should equal(f.appId.toString)
      (json \ "instanceId").as[String] should equal(f.instanceId.idString)
      (json \ "version").as[String] should equal(f.version.toString)
      (json \ "alive").as[Boolean] should equal(true)
      (json \ "eventType").as[String] should equal("health_status_changed_event")
      (json \ "timestamp").as[String] should equal(f.now.toString)
    }
  }

  class Fixture {
    val appId = PathId("/test")
    val version = Timestamp(1)
    val now = Timestamp(2)
    val instanceId = Instance.Id.forRunSpec(appId)
    val taskId = Task.Id.forInstanceId(instanceId, container = None)
    val h1 = Health(instanceId)

    val h2 = Health(
      instanceId = instanceId,
      consecutiveFailures = 0,
      firstSuccess = Some(Timestamp(1)),
      lastSuccess = Some(Timestamp(3)),
      lastFailure = Some(Timestamp(2))
    )

    val h3 = Health(
      instanceId,
      consecutiveFailures = 1,
      firstSuccess = Some(Timestamp(1)),
      lastSuccess = Some(Timestamp(2)),
      lastFailure = Some(Timestamp(3))
    )
  }
}
