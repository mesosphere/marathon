package mesosphere.marathon
package core.instance

import mesosphere.UnitTest
import mesosphere.marathon.state.UnreachableStrategy
import play.api.libs.json._

import scala.concurrent.duration._

class InstanceFormatTest extends UnitTest {

  import Instance._

  "Instance.unreachableStrategyFormat" should {
    "parse a proper JSON" in {
      val json = Json.parse("""{ "unreachableInactiveAfter": 1, "unreachableExpungeAfter": 2 }""")
      json.as[UnreachableStrategy].unreachableInactiveAfter should be(1.second)
      json.as[UnreachableStrategy].unreachableExpungeAfter should be(2.seconds)
    }

    "not parse a JSON with empty fields" in {
      val json = Json.parse("""{ "unreachableExpungeAfter": 2 }""")
      a[JsResultException] should be thrownBy { json.as[UnreachableStrategy] }
    }

  }

  "Instance.instanceFormat" should {
    "fill UnreachableStrategy with defaults if empty" in {
      val json = Json.parse(
        """{ "instanceId": { "idString": "app.instance-1337" },
          |  "tasksMap": {},
          |  "runSpecVersion": "2015-01-01",
          |  "agentInfo": { "host": "localhost", "attributes": [] },
          |  "state": { "since": "2015-01-01", "condition": { "str": "Running" } }
          |}""".stripMargin)
      val instance = json.as[Instance]

      instance.unreachableStrategy.unreachableInactiveAfter should be(UnreachableStrategy.DefaultTimeUntilInactive)
      instance.unreachableStrategy.unreachableExpungeAfter should be(UnreachableStrategy.DefaultTimeUntilExpunge)
    }
  }
}
