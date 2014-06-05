package mesosphere.marathon.upgrade

import akka.testkit.{ TestActor, TestProbe, TestKit }
import org.scalatest.{ Matchers, FunSuiteLike }
import akka.actor.{ ActorRef, Props, ActorSystem }
import scala.concurrent.{ Await, Promise }
import scala.concurrent.duration._
import akka.testkit.TestActor.{ NoAutoPilot, AutoPilot }
import mesosphere.marathon.upgrade.AppUpgradeActor.Cancel

class StopActorTest extends TestKit(ActorSystem("System")) with FunSuiteLike with Matchers {

  test("Stop") {
    val promise = Promise[Boolean]()
    val probe = TestProbe()

    probe.setAutoPilot(new AutoPilot {
      def run(sender: ActorRef, msg: Any): AutoPilot = msg match {
        case Cancel(reason) =>
          system.stop(probe.ref)
          NoAutoPilot
      }
    })
    val ref = system.actorOf(Props(classOf[StopActor], probe.ref, promise, new Exception))

    watch(ref)
    expectTerminated(ref)

    Await.result(promise.future, 5.seconds) should be(true)
  }
}
