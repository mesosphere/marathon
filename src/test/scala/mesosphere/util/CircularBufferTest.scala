package mesosphere.util

import org.scalatest.{ Matchers, WordSpecLike }

class CircularBufferTest extends WordSpecLike with Matchers {
  "A CircularBuffer" should {
    "allow to add n>size times" in {
      val cb = new CircularBuffer[String](3)

      cb.add("A")
      cb.add("B")
      cb.add("C")
      cb.add("D")

      cb.size shouldEqual 3
    }

    "show next value" in {
      val cb = new CircularBuffer[String](3)

      cb.add("A")
      cb.add("B")
      cb.add("C")

      cb.nextValue shouldEqual "A"
    }

    "provide iterator" in {
      val cb = new CircularBuffer[String](2)

      cb.add("A")
      cb.add("B")
      cb.add("C")

      val result = cb.iterator.toSeq

      result should contain allOf ("B", "C")
    }

    "provide access by index" in {
      val cb = new CircularBuffer[String](3)

      cb.add("A")
      cb.add("B")
      cb.add("C")

      cb(-1) shouldEqual ("C")
      cb(0) shouldEqual ("A")
    }
  }
}
