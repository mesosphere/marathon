package mesosphere.marathon
package core.leadership.impl

import akka.actor.{ PoisonPill, Props }
import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.leadership.PreparationMessages

import scala.concurrent.duration._

class WhenLeaderActorTest extends AkkaUnitTest {
  case class Fixture(childProbe: TestProbe = TestProbe()) {
    val childProps: Props = ProbeActor.props(childProbe)
    val whenLeaderRef: TestActorRef[WhenLeaderActor] = {
      val whenLeaderProps: Props = WhenLeaderActor.props(childProps)
      TestActorRef(whenLeaderProps)
    }
  }

  "WhenLeaderActor" should {
    "when suspended, delay all unknown messages" in new Fixture {
      val ref = whenLeaderRef
      val probe = TestProbe()
      probe.send(ref, "Message1")
      probe.send(ref, "Message2")
      childProbe.expectNoMsg(50.millis)
      probe.send(ref, PreparationMessages.PrepareForStart)
      childProbe.expectMsgClass(classOf[ProbeActor.PreStart])
      childProbe.expectMsg("Message1")
      childProbe.expectMsg("Message2")
    }

    "when suspended, respond to stop with stopped" in new Fixture {
      val ref = whenLeaderRef
      val probe = TestProbe()
      probe.send(ref, WhenLeaderActor.Stop)
      probe.expectMsg(WhenLeaderActor.Stopped)
    }

    "when suspended with preparedOnStart==true, change to active on start" in new Fixture {
      val ref = whenLeaderRef
      val probe = TestProbe()
      probe.send(ref, PreparationMessages.PrepareForStart)
      childProbe.expectMsgClass(classOf[ProbeActor.PreStart])
      probe.expectMsg(PreparationMessages.Prepared(ref))
      probe.send(ref, "Hi!")
      childProbe.expectMsg("Hi!")
      childProbe.reply("Hi, too!")
      probe.expectMsg("Hi, too!")
    }

    "when active, answer PrepareForStart immediately" in new Fixture {
      val probe = TestProbe()
      val ref = whenLeaderRef
      ref.underlying.become(ref.underlyingActor.active(childRef = childProbe.ref))
      probe.send(ref, PreparationMessages.PrepareForStart)
      probe.expectMsg(PreparationMessages.Prepared(ref))
    }

    "when active, stop" in new Fixture {
      val probe = TestProbe()
      val ref = whenLeaderRef
      ref.underlying.become(ref.underlyingActor.active(childProbe.ref))
      probe.send(ref, WhenLeaderActor.Stop)
      probe.expectMsg(WhenLeaderActor.Stopped)
    }

    "when dying, stash messages" in new Fixture {
      val ref = whenLeaderRef
      val probe = TestProbe()

      val dyingProbe = TestProbe()

      ref.underlying.become(ref.underlyingActor.dying(stopAckRef = probe.ref, childRef = dyingProbe.ref))
      ref.underlying.watch(dyingProbe.ref)

      val stashMeWhileStopping: String = "Stash me for next life cycle"
      val sendToChildAfterRestart: String = "Send me to started child"

      probe.send(ref, stashMeWhileStopping)
      probe.send(ref, PreparationMessages.PrepareForStart)
      probe.send(ref, sendToChildAfterRestart)

      dyingProbe.ref ! PoisonPill
      probe.expectMsg(WhenLeaderActor.Stopped)
      childProbe.expectMsgClass(classOf[ProbeActor.PreStart])
      probe.expectMsg(PreparationMessages.Prepared(ref))
      childProbe.expectMsg(stashMeWhileStopping)
      childProbe.expectMsg(sendToChildAfterRestart)
    }
  }
}
