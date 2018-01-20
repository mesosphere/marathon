package mesosphere.marathon
package metrics

import mesosphere.UnitTest

class MultiTimerTest extends UnitTest {

  "The multi timer" should {
    "return a proper formatted string" in {
      val timer = new MultiTimer()

      val subTimerOne = new MultiTimer.Timer("one")
      subTimerOne.start = Some(10000000)
      subTimerOne.end = Some(50000000)
      timer.subTimers += subTimerOne

      val subTimerTwo = new MultiTimer.Timer("two")
      subTimerTwo.start = Some(40000000)
      subTimerTwo.end = Some(42000000)
      timer.subTimers += subTimerTwo

      val actual = timer.toString
      actual should be("one=40ms, two=2ms")
    }
  }
}
