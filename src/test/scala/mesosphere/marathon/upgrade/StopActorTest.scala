package mesosphere.marathon.upgrade

import akka.testkit.{TestProbe, TestKit}
import org.scalatest.{Matchers, FunSuiteLike}
import akka.actor.{Props, ActorSystem}
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._

class StopActorTest extends TestKit(ActorSystem("System")) with FunSuiteLike with Matchers {

  test("Stop") {
    val promise = Promise[Boolean]()
    val probe = TestProbe()
    val ref = system.actorOf(Props(classOf[StopActor], probe.ref, promise))

    watch(ref)
    expectTerminated(ref)

    Await.result(promise.future, 1.second) should be(true)
  }
}
