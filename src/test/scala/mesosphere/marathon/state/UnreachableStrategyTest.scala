package mesosphere.marathon
package state

import mesosphere.UnitTest
import com.wix.accord.scalatest.ResultMatchers

import scala.concurrent.duration._

class UnreachableStrategyTest extends UnitTest with ResultMatchers {

  def validate = UnreachableStrategy.unreachableStrategyValidator

  "UnreachableStrategy.unreachableStrategyValidator" should {
    "validate default strategy" in {
      val strategy = UnreachableEnabled()
      validate(strategy) shouldBe aSuccess
    }

    "validate with other parameters successfully" in {
      val strategy = UnreachableEnabled(13.minutes, 37.minutes)
      validate(strategy) shouldBe aSuccess
    }

    "sees disabled as valid" in {
      val strategy = UnreachableDisabled
      validate(strategy) shouldBe aSuccess
    }

    "fail when time until expunge is smaller" in {
      val strategy = UnreachableEnabled(inactiveAfter = 2.seconds, expungeAfter = 1.second)
      validate(strategy) should failWith(
        "inactiveAfter" -> "got 2 seconds, expected less than 1 second")
    }

    "fail when time until expunge is equal to time until inactive" in {
      val strategy = UnreachableEnabled(inactiveAfter = 2.seconds, expungeAfter = 2.seconds)
      validate(strategy) should failWith(
        "inactiveAfter" -> "got 2 seconds, expected less than 2 seconds")
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
