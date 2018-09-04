package mesosphere.marathon
package core.instance

import mesosphere.UnitTest
import org.scalatest.prop.TableDrivenPropertyChecks
import play.api.libs.json.{JsString, JsSuccess}

class GoalTest extends UnitTest with TableDrivenPropertyChecks {

  "The Goal format" should {
    val goals = Table(
      ("serialized", "deserialized"),
      ("Running", Goal.Running),
      ("Stopped", Goal.Stopped),
      ("Decommissioned", Goal.Decommissioned)
    )

    forAll (goals) { (serialized, deserialized) =>
      s"deserialize $serialized" in {
        Given(s"$serialized as JSON string")
        val json = JsString(serialized)

        When("deserializing")
        val result = Goal.goalFormat.reads(json)

        Then(s"the result is $deserialized")
        result should be(JsSuccess(deserialized))
      }

      s"serialize $deserialized" in {
        Given(s"goal $deserialized")
        val goal = deserialized

        When("serializing")
        val json = Goal.goalFormat.writes(goal)

        When(s"the JSON is '$serialized'")
        json should be(JsString(serialized))
      }
    }
  }
}
