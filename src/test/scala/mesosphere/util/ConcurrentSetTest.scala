package mesosphere.util

import java.util.concurrent.Executors

import mesosphere.UnitTest

import scala.concurrent.{ ExecutionContext, Future }

class ConcurrentSetTest extends UnitTest {

  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  "A ConcurrentSet" should {
    "contain all values" in {
      val set = ConcurrentSet[Int]()

      val futures = for (i <- 0 until 10) yield Future {
        val start = i * 100000
        val end = start + 100000
        for (i <- start until end) set.add(i)
      }

      Future.sequence(futures).futureValue

      set.size should be(1000000)
      (0 until 1000000).forall(set) should be(true)
    }

    "contain no duplicate values" in {
      val set = ConcurrentSet[Int]()

      val futures = for (i <- 0 until 10) yield Future {
        for (i <- 0 until 100000) set.add(i)
      }

      Future.sequence(futures).futureValue

      set.size should be(100000)
      (0 until 100000).forall(set) should be(true)
    }

    "contain all added and none of the removed values" in {
      val set = ConcurrentSet[Int](0 until 500000: _*)

      val addFutures = for (i <- 5 until 10) yield Future {
        val start = i * 100000
        val end = start + 100000
        for (i <- start until end) set.add(i)
      }

      val removeFutures = for (i <- 0 until 5) yield Future {
        val start = i * 100000
        val end = start + 100000
        for (i <- start until end) set.remove(i)
      }

      Future.sequence(addFutures ++ removeFutures).futureValue

      set.size should be(500000)
      (500000 until 1000000).forall(set) should be(true)
      (0 until 500000).exists(set) should be(false)
    }
  }
}
