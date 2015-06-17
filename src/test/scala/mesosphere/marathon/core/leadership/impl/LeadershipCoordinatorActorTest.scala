package mesosphere.marathon.core.leadership.impl

import akka.actor.{ Status, PoisonPill, ActorSystem, Actor, Props }
import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.leadership.PreparationMessages
import scala.concurrent.duration._

class LeadershipCoordinatorActorTest extends MarathonSpec {
  test("in suspended, Stop doesn't do anything") {
    val probe = TestProbe()

    probe.send(coordinatorRef, WhenLeaderActor.Stop)

    whenLeader1Probe.expectNoMsg(0.seconds)
    whenLeader2Probe.expectNoMsg(0.seconds)
  }

  test("in preparingForStart, Stop is send to all whenLeaderActors and preparation is aborted") {
    val probe = TestProbe()

    coordinatorRef.underlying.become(
      coordinatorRef.underlyingActor.preparingForStart(Set(probe.ref), Set(whenLeader1Probe.ref)))

    probe.send(coordinatorRef, WhenLeaderActor.Stop)

    whenLeader1Probe.expectMsg(WhenLeaderActor.Stop)
    whenLeader2Probe.expectMsg(WhenLeaderActor.Stop)

    probe.expectMsgAnyClassOf(classOf[Status.Failure])
  }

  test("in active, Stop is send to all whenLeaderActors and preparation is aborted") {
    val probe = TestProbe()

    coordinatorRef.underlying.become(coordinatorRef.underlyingActor.active)

    probe.send(coordinatorRef, WhenLeaderActor.Stop)

    whenLeader1Probe.expectMsg(WhenLeaderActor.Stop)
    whenLeader2Probe.expectMsg(WhenLeaderActor.Stop)
    probe.expectNoMsg(0.seconds)

    // check we are in suspend
    probe.send(coordinatorRef, WhenLeaderActor.Stop)
    probe.expectNoMsg(0.seconds)
  }

  test("in suspended, remove terminated whenLeaderActors") {
    val probe = TestProbe()

    probe.send(whenLeader1Probe.ref, PoisonPill)

    assert(coordinatorRef.underlyingActor.whenLeaderActors == Set(whenLeader2Probe.ref))
  }

  test("in prepareToStart, remove terminated whenLeaderActors") {
    val probe = TestProbe()

    coordinatorRef.underlying.become(
      coordinatorRef.underlyingActor.preparingForStart(Set(probe.ref), Set(whenLeader1Probe.ref)))
    probe.send(whenLeader1Probe.ref, PoisonPill)

    assert(coordinatorRef.underlyingActor.whenLeaderActors == Set(whenLeader2Probe.ref))
    whenLeader2Probe.send(coordinatorRef, PreparationMessages.Prepared(whenLeader2Probe.ref))

    probe.expectMsg(PreparationMessages.Prepared(coordinatorRef))
  }

  test("in active, remove terminated whenLeaderActors") {
    val probe = TestProbe()

    coordinatorRef.underlying.become(coordinatorRef.underlyingActor.active)
    probe.send(whenLeader1Probe.ref, PoisonPill)

    assert(coordinatorRef.underlyingActor.whenLeaderActors == Set(whenLeader2Probe.ref))
  }

  test("switch to prepareForStart and wait for all actors to prepare until started") {
    val probe = TestProbe()

    probe.send(coordinatorRef, PreparationMessages.PrepareForStart)

    whenLeader1Probe.expectMsg(PreparationMessages.PrepareForStart)
    whenLeader2Probe.expectMsg(PreparationMessages.PrepareForStart)

    probe.expectNoMsg(0.seconds)

    whenLeader1Probe.reply(PreparationMessages.Prepared(whenLeader1Probe.ref))

    probe.expectNoMsg(0.seconds)

    whenLeader2Probe.reply(PreparationMessages.Prepared(whenLeader2Probe.ref))

    probe.expectMsg(PreparationMessages.Prepared(coordinatorRef))
  }

  test("when preparingForStart with one requester, add another interested actorRef if necessary") {
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

  test("when active, immediately answer PrepareToStart") {
    val probe = TestProbe()
    coordinatorRef.underlying.become(coordinatorRef.underlyingActor.active)

    probe.send(coordinatorRef, PreparationMessages.PrepareForStart)
    probe.expectMsg(PreparationMessages.Prepared(coordinatorRef))
  }

  private[this] implicit var actorSystem: ActorSystem = _
  private[this] var whenLeader1Probe: TestProbe = _
  private[this] var whenLeader2Probe: TestProbe = _
  private[this] var coordinatorRef: TestActorRef[LeadershipCoordinatorActor] = _

  before {
    actorSystem = ActorSystem()
    whenLeader1Probe = TestProbe()
    whenLeader2Probe = TestProbe()
    coordinatorRef = TestActorRef(LeadershipCoordinatorActor.props(Set(whenLeader1Probe.ref, whenLeader2Probe.ref)))

    coordinatorRef.start()
  }

  after {
    coordinatorRef.stop()

    actorSystem.shutdown()
    actorSystem.awaitTermination()
  }
}
