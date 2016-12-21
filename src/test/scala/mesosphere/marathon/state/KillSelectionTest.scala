package mesosphere.marathon
package state

import mesosphere.UnitTest

class KillSelectionTest extends UnitTest {

  "UnreachableStrategy.KillSelection" should {

    "parse all value 'YoungestFirst'" in {
      KillSelection.withName("YOUNGEST_FIRST") should be(KillSelection.YoungestFirst)
    }

    "parse all value 'OldestFirst'" in {
      KillSelection.withName("OLDEST_FIRST") should be(KillSelection.OldestFirst)
    }
  }

  it should {
    "throw an exception for an invalid value" in {
      the[NoSuchElementException] thrownBy {
        KillSelection.withName("youngestFirst")
      } should have message ("There is no KillSelection with name 'youngestFirst'")
    }
  }

  "UnreachableStrategy.YoungestFirst" should {
    "select the younger timestamp" in {
      KillSelection.YoungestFirst(Timestamp.zero, Timestamp(1)) should be(false)
      KillSelection.YoungestFirst(Timestamp(1), Timestamp.zero) should be(true)
    }
  }

  "UnreachableStrategy.OldestFirst" should {
    "select the older timestamp" in {
      KillSelection.OldestFirst(Timestamp.zero, Timestamp(1)) should be(true)
      KillSelection.OldestFirst(Timestamp(1), Timestamp.zero) should be(false)
    }
  }
}
