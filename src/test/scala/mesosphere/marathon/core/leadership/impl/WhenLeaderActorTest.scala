package mesosphere.marathon
package core.leadership.impl

import akka.actor.{ PoisonPill, Props, Status }
import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.leadership.PreparationMessages

class WhenLeaderActorTest extends AkkaUnitTest {
  case class Fixture(childProbe: TestProbe = TestProbe()) {
    val childProps: Props = ProbeActor.props(childProbe)
    val whenLeaderRef: TestActorRef[WhenLeaderActor] = {
      val whenLeaderProps: Props = WhenLeaderActor.props(childProps)
      TestActorRef(whenLeaderProps)
    }
  }

  "WhenLeaderActor" should {
    "when suspended, respond to all unknown messages with failures" in new Fixture {
      val ref = whenLeaderRef
      val probe = TestProbe()
      probe.send(ref, "Message")
      probe.expectMsgClass(classOf[Status.Failure])
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

    "when starting, stop" in new Fixture {
      val probe = TestProbe()
      val ref = whenLeaderRef
      ref.underlying.become(ref.underlyingActor.starting(coordinatorRef = probe.ref, childRef = childProbe.ref))
      probe.send(ref, WhenLeaderActor.Stop)
      val failure = probe.expectMsgClass(classOf[Status.Failure])
      assert(failure.cause.getMessage.contains("starting aborted due to stop"))
      probe.expectMsg(WhenLeaderActor.Stopped)
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

      val stashMeThenFail: String = "Stash me, then respond with fail"
      val sendToChildAfterRestart: String = "Send me to started child"

      probe.send(ref, stashMeThenFail)
      probe.send(ref, PreparationMessages.PrepareForStart)
      probe.send(ref, sendToChildAfterRestart)

      dyingProbe.ref ! PoisonPill
      probe.expectMsg(WhenLeaderActor.Stopped)
      probe.expectMsgClass(classOf[Status.Failure]) // response to stashMeThenFail
      childProbe.expectMsgClass(classOf[ProbeActor.PreStart])
      probe.expectMsg(PreparationMessages.Prepared(ref))
      childProbe.expectMsg(sendToChildAfterRestart)
    }
  }
}
