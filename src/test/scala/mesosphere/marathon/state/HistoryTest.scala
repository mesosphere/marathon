package mesosphere.marathon.state

import org.junit.Test
import org.junit.Assert._

class HistoryTest {

  class A(val version: Timestamp = Timestamp.now) extends Timestamped

  val a1 = new A(Timestamp(1393989019980L))
  val a2 = new A(Timestamp(1393989019981L))
  val a3 = new A(Timestamp(1393989019982L))

  def withFixture(f: History[A] => Unit) = f(History(a2, a3, a1))

  @Test
  def testOrdering() = withFixture { history =>
    assertEquals(history.toSeq, Seq(a1, a2, a3))
  }

  @Test
  def testContains() = withFixture { history =>
    assertTrue(history contains a2)
  }

  @Test
  def testAddition() = withFixture { history =>
    assertEquals((history + new A(Timestamp.now)).size, 4)
  }

  @Test
  def testSubtraction() = withFixture { history =>
    val difference = history - a2
    assertEquals(difference.size, 2)
    assertFalse(difference contains a2)
  }

}