package mesosphere.marathon
package state

import mesosphere.UnitTest

class KillSelectionTest extends UnitTest {

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
