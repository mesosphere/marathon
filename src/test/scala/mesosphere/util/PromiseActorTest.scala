package mesosphere.util

import akka.actor.{ Props, Status }
import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.marathon.test.{ MarathonActorSupport, MarathonSpec }
import org.scalatest.{ BeforeAndAfter, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }

class PromiseActorTest
    extends MarathonActorSupport
    with MarathonSpec
    with BeforeAndAfter
    with Matchers {

  test("Success") {
    val promise = Promise[Any]()
    val ref = TestActorRef[PromiseActor](Props(classOf[PromiseActor], promise))

    ref ! 'Test

    Await.result(promise.future, 2.seconds) should equal('Test)
  }

  test("Success with askWithoutTimeout") {
    val probe = TestProbe()
    val future: Future[Symbol] = PromiseActor.askWithoutTimeout(system, probe.ref, 'Question)
    probe.expectMsg('Question)
    probe.reply('Answer)

    Await.result(future, 2.seconds) should equal('Answer)
  }

  test("Status.Success") {
    val promise = Promise[Any]()
    val ref = TestActorRef[PromiseActor](Props(classOf[PromiseActor], promise))

    ref ! Status.Success('Test)

    Await.result(promise.future, 2.seconds) should equal('Test)
  }

  test("State.Success with askWithoutTimeout") {
    val probe = TestProbe()
    val future: Future[Symbol] = PromiseActor.askWithoutTimeout(system, probe.ref, 'Question)
    probe.expectMsg('Question)
    probe.reply(Status.Success('Answer))

    Await.result(future, 2.seconds) should equal('Answer)
  }

  test("Status.Failure") {
    val promise = Promise[Any]()
    val ref = TestActorRef[PromiseActor](Props(classOf[PromiseActor], promise))
    val ex = new Exception("test")

    ref ! Status.Failure(ex)

    intercept[Exception] {
      Await.result(promise.future, 2.seconds)
    }.getMessage should be("test")
  }

  test("State.Failure with askWithoutTimeout") {
    val probe = TestProbe()
    val future: Future[Symbol] = PromiseActor.askWithoutTimeout(system, probe.ref, 'Question)
    probe.expectMsg('Question)
    probe.reply(Status.Failure(new IllegalStateException("error")))

    intercept[IllegalStateException] {
      Await.result(future, 2.seconds)
    }.getMessage should be("error")
  }

}
