package mesosphere.marathon.upgrade

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.testkit.TestActor.{ AutoPilot, NoAutoPilot }
import akka.testkit.{ TestKit, TestProbe }
import mesosphere.marathon.upgrade.DeploymentActor.Cancel
import org.scalatest.{ FunSuiteLike, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

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
