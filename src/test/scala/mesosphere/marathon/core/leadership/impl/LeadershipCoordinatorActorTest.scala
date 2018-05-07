package mesosphere.marathon
package core.leadership.impl

import akka.actor.{PoisonPill, Status}
import akka.testkit.{TestActorRef, TestProbe}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.leadership.PreparationMessages

import scala.concurrent.duration._

class LeadershipCoordinatorActorTest extends AkkaUnitTest {

  case class Fixture(
      whenLeader1Probe: TestProbe = TestProbe(),
      whenLeader2Probe: TestProbe = TestProbe()) {

    val coordinatorRef: TestActorRef[LeadershipCoordinatorActor] = TestActorRef(LeadershipCoordinatorActor.props(Set(whenLeader1Probe.ref, whenLeader2Probe.ref)))
    coordinatorRef.start()
  }

  "LeadershipCoordinatorActor" should {
    "in suspended, Stop doesn't do anything" in new Fixture {
      val probe = TestProbe()

      probe.send(coordinatorRef, WhenLeaderActor.Stop)

      whenLeader1Probe.expectNoMessage(0.seconds)
      whenLeader2Probe.expectNoMessage(0.seconds)
    }

    "in preparingForStart, Stop is send to all whenLeaderActors and preparation is aborted" in new Fixture {
      val probe = TestProbe()

      coordinatorRef.underlying.become(
        coordinatorRef.underlyingActor.preparingForStart(Set(probe.ref), Set(whenLeader1Probe.ref)))

      probe.send(coordinatorRef, WhenLeaderActor.Stop)

      whenLeader1Probe.expectMsg(WhenLeaderActor.Stop)
      whenLeader2Probe.expectMsg(WhenLeaderActor.Stop)

      probe.expectMsgAnyClassOf(classOf[Status.Failure])
    }

    "in active, Stop is send to all whenLeaderActors and preparation is aborted" in new Fixture {
      val probe = TestProbe()

      coordinatorRef.underlying.become(coordinatorRef.underlyingActor.active)

      probe.send(coordinatorRef, WhenLeaderActor.Stop)

      whenLeader1Probe.expectMsg(WhenLeaderActor.Stop)
      whenLeader2Probe.expectMsg(WhenLeaderActor.Stop)
      probe.expectNoMessage(0.seconds)

      // check we are in suspend
      probe.send(coordinatorRef, WhenLeaderActor.Stop)
      probe.expectNoMessage(0.seconds)
    }

    "in suspended, remove terminated whenLeaderActors" in new Fixture {
      val probe = TestProbe()

      probe.send(whenLeader1Probe.ref, PoisonPill)

      assert(coordinatorRef.underlyingActor.whenLeaderActors == Set(whenLeader2Probe.ref))
    }

    "in prepareToStart, remove terminated whenLeaderActors" in new Fixture {
      val probe = TestProbe()

      coordinatorRef.underlying.become(
        coordinatorRef.underlyingActor.preparingForStart(Set(probe.ref), Set(whenLeader1Probe.ref)))
      probe.send(whenLeader1Probe.ref, PoisonPill)

      assert(coordinatorRef.underlyingActor.whenLeaderActors == Set(whenLeader2Probe.ref))
      whenLeader2Probe.send(coordinatorRef, PreparationMessages.Prepared(whenLeader2Probe.ref))

      probe.expectMsg(PreparationMessages.Prepared(coordinatorRef))
    }

    "in active, remove terminated whenLeaderActors" in new Fixture {
      val probe = TestProbe()

      coordinatorRef.underlying.become(coordinatorRef.underlyingActor.active)
      probe.send(whenLeader1Probe.ref, PoisonPill)

      assert(coordinatorRef.underlyingActor.whenLeaderActors == Set(whenLeader2Probe.ref))
    }

    "switch to prepareForStart and wait for all actors to prepare until started" in new Fixture {
      val probe = TestProbe()

      probe.send(coordinatorRef, PreparationMessages.PrepareForStart)

      whenLeader1Probe.expectMsg(PreparationMessages.PrepareForStart)
      whenLeader2Probe.expectMsg(PreparationMessages.PrepareForStart)

      probe.expectNoMessage(0.seconds)

      whenLeader1Probe.reply(PreparationMessages.Prepared(whenLeader1Probe.ref))

      probe.expectNoMessage(0.seconds)

      whenLeader2Probe.reply(PreparationMessages.Prepared(whenLeader2Probe.ref))

      probe.expectMsg(PreparationMessages.Prepared(coordinatorRef))
    }

    "when preparingForStart with one requester, add another interested actorRef if necessary" in new Fixture {
      val requester1 = TestProbe()
      val requester2 = TestProbe()

      coordinatorRef.underlying.become(
        coordinatorRef.underlyingActor.preparingForStart(
          Set(requester1.ref), Set(whenLeader1Probe.ref)))

      requester2.send(coordinatorRef, PreparationMessages.PrepareForStart)

      whenLeader1Probe.send(coordinatorRef, PreparationMessages.Prepared(whenLeader1Probe.ref))

      requester1.expectMsg(PreparationMessages.Prepared(coordinatorRef))
      requester2.expectMsg(PreparationMessages.Prepared(coordinatorRef))
    }

    "when active, immediately answer PrepareToStart" in new Fixture {
      val probe = TestProbe()
      coordinatorRef.underlying.become(coordinatorRef.underlyingActor.active)

      probe.send(coordinatorRef, PreparationMessages.PrepareForStart)
      probe.expectMsg(PreparationMessages.Prepared(coordinatorRef))
    }
  }
}
