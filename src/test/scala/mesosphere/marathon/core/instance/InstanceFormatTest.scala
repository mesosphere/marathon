package mesosphere.marathon
package core.instance

import mesosphere.UnitTest
import mesosphere.marathon.state.{ UnreachableStrategy, UnreachableDisabled, UnreachableEnabled }
import play.api.libs.json._

import scala.concurrent.duration._

class InstanceFormatTest extends UnitTest {
  import Instance._

  val template = Json.parse(
    """
      |{
      |  "instanceId": { "idString": "app.instance-1337" },
      |  "tasksMap": {},
      |  "runSpecVersion": "2015-01-01T12:00:00Z",
      |  "agentInfo": { "host": "localhost", "attributes": [] },
      |  "state": { "since": "2015-01-01T12:00:00Z", "condition": { "str": "Running" } }
      |}""".stripMargin).as[JsObject]

  "Instance.instanceFormat" should {
    "parse a valid unreachable strategy" in {
      val json = template ++ Json.obj(
        "unreachableStrategy" -> Json.obj(
          "inactiveAfterSeconds" -> 1, "expungeAfterSeconds" -> 2))
      val instance = json.as[Instance]

      instance.unreachableStrategy shouldBe (UnreachableEnabled(inactiveAfter = 1.second, expungeAfter = 2.seconds))
    }

    "parse a disabled unreachable strategy" in {
      val json = template ++ Json.obj("unreachableStrategy" -> "disabled")
      val instance = json.as[Instance]

      instance.unreachableStrategy shouldBe (UnreachableDisabled)
    }

    "fill UnreachableStrategy with defaults if empty" in {
      val instance = template.as[Instance]

      instance.unreachableStrategy shouldBe (UnreachableStrategy.default(resident = false))
    }
  }
}
