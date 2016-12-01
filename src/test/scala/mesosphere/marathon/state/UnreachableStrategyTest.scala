package mesosphere.marathon
package state

import mesosphere.UnitTest
import mesosphere.marathon.state.UnreachableStrategy.KillSelection.{ OldestFirst, YoungestFirst }
import com.wix.accord.scalatest.ResultMatchers

import scala.concurrent.duration._

class UnreachableStrategyTest extends UnitTest with ResultMatchers {

  "UnreachableStrategy.KillSelection" should {

    "parse all value 'YoungestFirst'" in {
      UnreachableStrategy.KillSelection.withName("YoungestFirst") should be(YoungestFirst)
    }

    "parse all value 'OldestFirst'" in {
      UnreachableStrategy.KillSelection.withName("OldestFirst") should be(OldestFirst)
    }
  }

  it should {
    "throw an exception for an invalid value" in {
      the[NoSuchElementException] thrownBy {
        UnreachableStrategy.KillSelection.withName("youngestFirst")
      } should have message ("There is no KillSelection with name 'youngestFirst'")
    }
  }

  "UnreachableStrategy.YoungestFirst" should {
    "select the younger timestamp" in {
      YoungestFirst(Timestamp.zero, Timestamp(1)) should be(false)
      YoungestFirst(Timestamp(1), Timestamp.zero) should be(true)
    }
  }

  "UnreachableStrategy.OldestFirst" should {
    "select the older timestamp" in {
      OldestFirst(Timestamp.zero, Timestamp(1)) should be(true)
      OldestFirst(Timestamp(1), Timestamp.zero) should be(false)
    }
  }

  "UnreachableStrategy.unreachableStrategyValidator" should {
    "validate default strategy" in {
      val strategy = UnreachableStrategy()
      UnreachableStrategy.unreachableStrategyValidator(strategy) shouldBe aSuccess
    }

    "validate with other paramters successfully" in {
      val strategy = UnreachableStrategy(13.minutes, 37.minutes)
      UnreachableStrategy.unreachableStrategyValidator(strategy) shouldBe aSuccess
    }

    "fail with invalid time until inactive" in {
      val strategy = UnreachableStrategy(timeUntilInactive = 0.second)
      UnreachableStrategy.unreachableStrategyValidator(strategy) should failWith("timeUntilInactive" -> "got 0 seconds, expected 1 second or more")
    }

    "fail when time until expunge is smaller" in {
      val strategy = UnreachableStrategy(timeUntilInactive = 2.seconds, timeUntilExpunge = 1.second)
      UnreachableStrategy.unreachableStrategyValidator(strategy) should failWith("timeUntilInactive" -> "got 2 seconds, expected less than 1 second")
    }

    "fail when time until expunge is equal to time until inactive" in {
      val strategy = UnreachableStrategy(timeUntilInactive = 2.seconds, timeUntilExpunge = 2.seconds)
      UnreachableStrategy.unreachableStrategyValidator(strategy) should failWith("timeUntilInactive" -> "got 2 seconds, expected less than 2 seconds")
    }
  }
}
