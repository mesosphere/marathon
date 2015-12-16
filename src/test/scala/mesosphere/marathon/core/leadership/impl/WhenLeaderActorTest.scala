package mesosphere.marathon.core.leadership.impl

import akka.actor.{ ActorSystem, PoisonPill, Props, Status }
import akka.testkit.{ TestActorRef, TestProbe }
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.leadership.PreparationMessages

class WhenLeaderActorTest extends MarathonSpec {
  test("when suspended, respond to all unknown messages with failures") {
    val ref = whenLeaderRef()
    val probe = TestProbe()
    probe.send(ref, "Message")
    probe.expectMsgClass(classOf[Status.Failure])
  }

  test("when suspended, respond to stop with stopped") {
    val ref = whenLeaderRef()
    val probe = TestProbe()
    probe.send(ref, WhenLeaderActor.Stop)
    probe.expectMsg(WhenLeaderActor.Stopped)
  }

  test("when suspended with preparedOnStart==true, change to active on start") {
    val ref = whenLeaderRef()
    val probe = TestProbe()
    probe.send(ref, PreparationMessages.PrepareForStart)
    childProbe.expectMsgClass(classOf[ProbeActor.PreStart])
    probe.expectMsg(PreparationMessages.Prepared(ref))
    probe.send(ref, "Hi!")
    childProbe.expectMsg("Hi!")
    childProbe.reply("Hi, too!")
    probe.expectMsg("Hi, too!")
  }

  test("when active, answer PrepareForStart immediately") {
    val probe = TestProbe()
    val ref = whenLeaderRef()
    ref.underlying.become(ref.underlyingActor.active(childRef = childProbe.ref))
    probe.send(ref, PreparationMessages.PrepareForStart)
    probe.expectMsg(PreparationMessages.Prepared(ref))
  }

  test("when starting, stop") {
    val probe = TestProbe()
    val ref = whenLeaderRef()
    ref.underlying.become(ref.underlyingActor.starting(coordinatorRef = probe.ref, childRef = childProbe.ref))
    probe.send(ref, WhenLeaderActor.Stop)
    val failure = probe.expectMsgClass(classOf[Status.Failure])
    assert(failure.cause.getMessage.contains("starting aborted due to stop"))
    probe.expectMsg(WhenLeaderActor.Stopped)
  }

  test("when active, stop") {
    val probe = TestProbe()
    val ref = whenLeaderRef()
    ref.underlying.become(ref.underlyingActor.active(childProbe.ref))
    probe.send(ref, WhenLeaderActor.Stop)
    probe.expectMsg(WhenLeaderActor.Stopped)
  }

  test("when dying, stash messages") {
    val ref = whenLeaderRef()
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

  private[this] implicit var actorSystem: ActorSystem = _
  private[this] var childProbe: TestProbe = _
  private[this] var childProps: Props = _
  private[this] def whenLeaderRef(): TestActorRef[WhenLeaderActor] = {
    val whenLeaderProps: Props = WhenLeaderActor.props(childProps)
    TestActorRef(whenLeaderProps, "whenLeader")
  }

  before {
    actorSystem = ActorSystem()
    childProbe = TestProbe()
    childProps = ProbeActor.props(childProbe)
  }

  after {
    actorSystem.shutdown()
    actorSystem.awaitTermination()
  }
}
