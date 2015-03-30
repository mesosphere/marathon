package mesosphere.util

import akka.actor.ActorSystem
import akka.testkit.TestKit
import mesosphere.marathon.MarathonSpec
import org.scalatest.Matchers
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._

class SerializeExecutionTest extends TestKit(ActorSystem("system")) with MarathonSpec with Matchers {
  test("submit successful futures") {
    val serialize = SerializeExecution(system, "serialize1")
    try {
      val future1: Future[Int] = serialize(Future.successful(1))
      val result1 = Await.result(future1, 3.seconds)
      result1 should be(1)
      val future2: Future[Int] = serialize(Future.successful(2))
      val result2 = Await.result(future2, 3.seconds)
      result2 should be(2)
      val future3: Future[Int] = serialize(Future.successful(3))
      val result3 = Await.result(future3, 3.seconds)
      result3 should be(3)
    }
    finally {
      serialize.close()
    }
  }

  test("submit successful futures after a failure") {
    val serialize = SerializeExecution(system, "serialize2")
    try {
      val future1: Future[Int] = serialize(Future.failed(new IllegalStateException()))
      a[IllegalStateException] should be thrownBy Await.result(future1, 3.seconds)
      val future2: Future[Int] = serialize(Future.successful(2))
      val result2 = Await.result(future2, 3.seconds)
      result2 should be(2)
      val future3: Future[Int] = serialize(Future.successful(3))
      val result3 = Await.result(future3, 3.seconds)
      result3 should be(3)
    }
    finally {
      serialize.close()
    }
  }

  test("submit successful futures after a failure to return future") {
    val serialize = SerializeExecution(system, "serialize3")
    try {
      val future1: Future[Int] = serialize(throw new IllegalStateException())
      a[IllegalStateException] should be thrownBy Await.result(future1, 3.seconds)
      val future2: Future[Int] = serialize(Future.successful(2))
      val result2 = Await.result(future2, 3.seconds)
      result2 should be(2)
      val future3: Future[Int] = serialize(Future.successful(3))
      val result3 = Await.result(future3, 3.seconds)
      result3 should be(3)
    }
    finally {
      serialize.close()
    }
  }
}
