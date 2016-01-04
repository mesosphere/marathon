package mesosphere.util

import akka.testkit.{ TestProbe, TestActorRef, TestKit }
import akka.actor.{ Status, Props, ActorSystem }
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.test.MarathonActorSupport
import org.scalatest.{ Matchers, BeforeAndAfterAll }
import scala.concurrent.{ Future, Await, Promise }
import scala.concurrent.duration._

class PromiseActorTest
    extends MarathonActorSupport
    with MarathonSpec
    with BeforeAndAfterAll
    with Matchers {

  test("Success") {
    val promise = Promise[Any]()
    val ref = TestActorRef(Props(classOf[PromiseActor], promise))

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
    val ref = TestActorRef(Props(classOf[PromiseActor], promise))

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
    val ref = TestActorRef(Props(classOf[PromiseActor], promise))
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
