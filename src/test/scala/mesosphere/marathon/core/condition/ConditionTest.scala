package mesosphere.marathon
package core.condition

import mesosphere.UnitTest

class ConditionTest extends UnitTest {

  "A Condition" when {
    "staging" should {
      val condition = Condition.Staging
      "be active" in { condition.isActive should be(true) }
      "not be terminal" in { condition.isTerminal should be(false) }
    }

    "starting" should {
      val condition = Condition.Starting
      "be active" in { condition.isActive should be(true) }
      "not be terminal" in { condition.isTerminal should be(false) }
    }

    "running" should {
      val condition = Condition.Running
      "be active" in { condition.isActive should be(true) }
      "not be terminal" in { condition.isTerminal should be(false) }
    }

    "unreachable" should {
      val condition = Condition.Unreachable
      "be active" in { condition.isActive should be(true) }
      "not be terminal" in { condition.isTerminal should be(false) }
    }

    "killing" should {
      val condition = Condition.Killing
      "be active" in { condition.isActive should be(true) }
      "not be terminal" in { condition.isTerminal should be(false) }
    }

    "provisioned" should {
      val condition = Condition.Provisioned
      "be active" in { condition.isActive should be(true) }
      "not be terminal" in { condition.isTerminal should be(false) }
    }

    "failed" should {
      val condition = Condition.Failed
      "not be active" in { condition.isActive should be(false) }
      "be terminal" in { condition.isTerminal should be(true) }
    }

    "gone" should {
      val condition = Condition.Gone
      "not be active" in { condition.isActive should be(false) }
      "be terminal" in { condition.isTerminal should be(true) }
    }

    "killed" should {
      val condition = Condition.Killed
      "not be active" in { condition.isActive should be(false) }
      "be terminal" in { condition.isTerminal should be(true) }
    }

    "finished" should {
      val condition = Condition.Finished
      "not be active" in { condition.isActive should be(false) }
      "be terminal" in { condition.isTerminal should be(true) }
    }

    "dropped" should {
      val condition = Condition.Dropped
      "not be active" in { condition.isActive should be(false) }
      "be terminal" in { condition.isTerminal should be(true) }
    }
  }

  "Condition apply/unapply" should {
    "round trip all instances" in {
      Condition.all.foreach { condition =>
        Condition(Condition.unapply(condition).get) shouldBe condition
      }
    }
  }

  "ConditionFormat" should {
    import play.api.libs.json._
    import Condition.conditionFormat
    "read the legacy format" in {
      Json.parse("""{"str":"Running"}""").as[Condition] shouldBe Condition.Running
    }

    "can round trip serialize a condition" in {
      Json.toJson(Condition.Error).as[Condition] shouldBe (Condition.Error)
    }
  }
}
