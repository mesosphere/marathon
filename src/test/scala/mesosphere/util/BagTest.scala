package mesosphere.util

import org.scalatest.{ Matchers, WordSpecLike }

class BagTest extends WordSpecLike with Matchers {

  "A Bag" should {
    "allow to add multitimes" in {
      val bag = new Bag[String]()

      bag.add("A")
      bag.add("B")
      bag.add("A")

      bag.allKeys should have size 2
      bag.count("A") shouldEqual 2
      bag.count("B") shouldEqual 1
    }

    "allow to remove" in {
      val bag = new Bag[String]()

      bag.add("A")
      bag.add("B")
      bag.add("C")
      bag.add("A")
      bag.delete("A", 2)

      bag.allKeys should have size 2
      bag.count("A") shouldEqual 0
      bag.count("C") shouldEqual 1
    }

    "contain allKeys" in {
      val bag = new Bag[String]()
      bag.add("A", 3)
      bag.add("B", 2)
      bag.add("D")

      bag.allKeys should have size 3
      bag.count("A") shouldEqual 3
      bag.count("B") shouldEqual 2
      bag.count("D") shouldEqual 1
    }
  }

}
