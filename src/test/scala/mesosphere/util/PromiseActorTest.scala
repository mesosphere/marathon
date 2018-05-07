package mesosphere.util

import akka.actor.{Props, Status}
import akka.testkit.{TestActorRef, TestProbe}
import mesosphere.AkkaUnitTest

import scala.concurrent.{Future, Promise}

class PromiseActorTest extends AkkaUnitTest {

  "PromiseActor" should {
    "Success" in {
      val promise = Promise[Any]()
      val ref = TestActorRef[PromiseActor](Props(classOf[PromiseActor], promise))

      ref ! 'Test

      promise.future.futureValue should equal('Test)
    }

    "Success with askWithoutTimeout" in {
      val probe = TestProbe()
      val future: Future[Symbol] = PromiseActor.askWithoutTimeout(system, probe.ref, 'Question)
      probe.expectMsg('Question)
      probe.reply('Answer)

      future.futureValue should equal('Answer)
    }

    "Status.Success" in {
      val promise = Promise[Any]()
      val ref = TestActorRef[PromiseActor](Props(classOf[PromiseActor], promise))

      ref ! Status.Success('Test)

      promise.future.futureValue should equal('Test)
    }

    "State.Success with askWithoutTimeout" in {
      val probe = TestProbe()
      val future: Future[Symbol] = PromiseActor.askWithoutTimeout(system, probe.ref, 'Question)
      probe.expectMsg('Question)
      probe.reply(Status.Success('Answer))

      future.futureValue should equal('Answer)
    }

    "Status.Failure" in {
      val promise = Promise[Any]()
      val ref = TestActorRef[PromiseActor](Props(classOf[PromiseActor], promise))
      val ex = new Exception("test")

      ref ! Status.Failure(ex)

      intercept[Exception] {
        throw promise.future.failed.futureValue
      }.getMessage should be("test")
    }

    "State.Failure with askWithoutTimeout" in {
      val probe = TestProbe()
      val future: Future[Symbol] = PromiseActor.askWithoutTimeout(system, probe.ref, 'Question)
      probe.expectMsg('Question)
      probe.reply(Status.Failure(new IllegalStateException("error")))

      intercept[IllegalStateException] {
        throw future.failed.futureValue
      }.getMessage should be("error")
    }
  }
}
