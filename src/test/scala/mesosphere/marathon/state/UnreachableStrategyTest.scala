package mesosphere.marathon
package state

import mesosphere.{ UnitTest, ValidationTestLike }

import scala.concurrent.duration._

class UnreachableStrategyTest extends UnitTest with ValidationTestLike {

  implicit def validator = UnreachableStrategy.unreachableStrategyValidator

  "UnreachableStrategy.unreachableStrategyValidator" should {
    "validate default strategy" in {
      val strategy = UnreachableEnabled()
      validator(strategy) shouldBe aSuccess
    }

    "validate with other parameters successfully" in {
      val strategy = UnreachableEnabled(13.minutes, 37.minutes)
      validator(strategy) shouldBe aSuccess
    }

    "sees disabled as valid" in {
      val strategy = UnreachableDisabled
      validator(strategy) shouldBe aSuccess
    }

    "fail when time until expunge is smaller" in {
      val strategy = UnreachableEnabled(inactiveAfter = 2.seconds, expungeAfter = 1.second)
      validator(strategy) should haveViolations("/inactiveAfter" -> "got 2 seconds, expected 1 second or less")
    }

    "succeed when time until expunge is equal to time until inactive" in {
      val strategy = UnreachableEnabled(inactiveAfter = 2.seconds, expungeAfter = 2.seconds)
      validator(strategy) shouldBe aSuccess
    }
  }

  "toProto" should {
    Seq(
      UnreachableDisabled,
      UnreachableEnabled(inactiveAfter = 10.seconds, expungeAfter = 20.seconds)).foreach { unreachableStrategy =>

        s"round trip serializes ${unreachableStrategy}" in {
          UnreachableStrategy.fromProto(unreachableStrategy.toProto) shouldBe (unreachableStrategy)
        }
      }
  }
}
