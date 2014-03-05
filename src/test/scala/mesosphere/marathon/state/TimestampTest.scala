package mesosphere.marathon.state

import org.junit.Test
import org.junit.Assert._

class TimestampTest {

  @Test
  def testOrdering() {
    val t1 = Timestamp(1024)
    val t2 = Timestamp(2048)
    assertTrue(t1.compare(t2) < 0)
  }

}