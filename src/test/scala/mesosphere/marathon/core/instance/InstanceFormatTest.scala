package mesosphere.marathon
package core.instance

import mesosphere.UnitTest
import mesosphere.marathon.state.UnreachableStrategy
import play.api.libs.json._

import scala.concurrent.duration._

class InstanceFormatTest extends UnitTest {

  import Instance._

  "Instance.KillSelectionFormat" should {
    "create a proper JSON object from YoungestFirst" in {
      val json = Json.toJson(UnreachableStrategy.KillSelection.YoungestFirst)
      json.as[String] should be("YoungestFirst")
    }

    "create a proper JSON object from OldestFirst" in {
      val json = Json.toJson(UnreachableStrategy.KillSelection.OldestFirst)
      json.as[String] should be("OldestFirst")
    }
  }

  "Instance.unreachableStrategyFormat" should {
    "parse a proper JSON" in {
      val json = Json.parse("""{ "timeUntilInactive": 1, "timeUntilExpunge": 2, "killSelection": "YoungestFirst" }""")
      json.as[UnreachableStrategy].killSelection should be(UnreachableStrategy.KillSelection.YoungestFirst)
      json.as[UnreachableStrategy].timeUntilInactive should be(1.second)
      json.as[UnreachableStrategy].timeUntilExpunge should be(2.seconds)
    }

    "not parse a JSON with empty fields" in {
      val json = Json.parse("""{ "timeUntilExpunge": 2 }""")
      a[JsResultException] should be thrownBy { json.as[UnreachableStrategy] }
    }

    "fail on an invalid kill selection" in {
      val json = Json.parse("""{ "timeUntilInactive": 1, "timeUntilExpunge": 2, "killSelection": "youngestFirst" }""")
      the[JsResultException] thrownBy {
        json.as[UnreachableStrategy]
      } should have message ("JsResultException(errors:List((/killSelection,List(ValidationError(List(There is no KillSelection with name 'youngestFirst'),WrappedArray())))))")
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

      instance.unreachableStrategy.killSelection should be(UnreachableStrategy.DefaultKillSelection)
      instance.unreachableStrategy.timeUntilInactive should be(UnreachableStrategy.DefaultTimeUntilInactive)
      instance.unreachableStrategy.timeUntilExpunge should be(UnreachableStrategy.DefaultTimeUntilExpunge)
    }
  }
}
