package mesosphere.marathon
package state

import mesosphere.UnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Goal
import mesosphere.marathon.core.instance.Instance.{Id, InstanceState}
import play.api.libs.json._

class InstanceFormatTest extends UnitTest {
  import Instance._

  val template = Json.parse(
    """
      |{
      |  "instanceId": { "idString": "app.instance-b6ff5fa5-7714-11e7-a55c-5ecf1c4671f6" },
      |  "tasksMap": {},
      |  "runSpecVersion": "2015-01-01T12:00:00.000Z",
      |  "agentInfo": { "host": "localhost", "attributes": [] },
      |  "state": { "since": "2015-01-01T12:00:00.000Z", "condition": { "str": "Running" }, "goal": "Running" }
      |}""".stripMargin).as[JsObject]

  "Instance.instanceFormat" should {
    "ignore unreachable strategy when reading" in {
      val json = template ++ Json.obj(
        "unreachableStrategy" -> Json.obj(
          "inactiveAfterSeconds" -> 1, "expungeAfterSeconds" -> 2))
      val instance = json.asOpt[Instance]

      instance should be('defined)
    }

    "not write out the unreachable strategy" in {
      val state = InstanceState(Condition.Running, Timestamp.now(), None, None, Goal.Running)
      val instance = Instance(Id.forRunSpec(PathId("/app")), None, state, Map.empty, Timestamp.now(), None)

      val json = Json.toJson(instance)

      json.as[JsObject].value should not contain key("unreachableStrategy")
    }
  }
}
