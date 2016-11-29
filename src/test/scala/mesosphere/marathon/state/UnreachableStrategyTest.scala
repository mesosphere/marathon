package mesosphere.marathon.state

import mesosphere.UnitTest
import mesosphere.marathon.state.UnreachableStrategy.KillSelection.{ OldestFirst, YoungestFirst }

class UnreachableStrategyTest extends UnitTest {

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
}
