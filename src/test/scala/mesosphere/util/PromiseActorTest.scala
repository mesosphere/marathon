package mesosphere.util

import akka.testkit.{ TestActorRef, TestKit }
import akka.actor.{ Status, Props, ActorSystem }
import mesosphere.marathon.MarathonSpec
import org.scalatest.{ Matchers, BeforeAndAfterAll }
import scala.concurrent.{ Await, Promise }
import scala.concurrent.duration._

class PromiseActorTest
    extends TestKit(ActorSystem("System"))
    with MarathonSpec
    with BeforeAndAfterAll
    with Matchers {

  override def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  test("Success") {
    val promise = Promise[Any]()
    val ref = TestActorRef(Props(classOf[PromiseActor], promise))

    ref ! 'Test

    Await.result(promise.future, 2.seconds) should equal('Test)
  }

  test("Status.Success") {
    val promise = Promise[Any]()
    val ref = TestActorRef(Props(classOf[PromiseActor], promise))

    ref ! Status.Success('Test)

    Await.result(promise.future, 2.seconds) should equal('Test)
  }

  test("Status.Failure") {
    val promise = Promise[Any]()
    val ref = TestActorRef(Props(classOf[PromiseActor], promise))
    val ex = new Exception("test")

    ref ! Status.Failure(ex)

    intercept[Exception] {
      Await.result(promise.future, 2.seconds)
    }.getMessage should be("test")
  }
}
