package mesosphere.marathon.core.heartbeat

import akka.actor._
import akka.testkit._
import mesosphere.AkkaUnitTest

import scala.language.postfixOps
import scala.concurrent.duration._

class HeartbeatActorTest extends AkkaUnitTest with TestKitBase with ImplicitSender {

  import Heartbeat._
  import HeartbeatInternal._
  import HeartbeatActorTest._

  lazy val fakeTimeout = 30 seconds // don't set to zero, unless you want to guarantee a timeout condition
  lazy val fakeThreshold = 2
  lazy val fakeConfig = Config(fakeTimeout, fakeThreshold, reactorDecorator = None)

  "a HeartbeatActor" must {

    "initialize to inactive state" in {
      val fsm = TestFSMRef(new HeartbeatActor(fakeConfig))
      fsm.stateName should be (StateInactive)
      fsm.stateData should be (DataNone)
    }

    "ignore non-activation messages when inactive" in {
      val fsm = TestFSMRef(new HeartbeatActor(fakeConfig))
      fsm ! MessageDeactivate(None)
      fsm ! MessagePulse
      fsm.stateName should be (StateInactive)
      fsm.stateData should be (DataNone)
    }

    "activate upon receipt of an activation message" in {
      val fsm = TestFSMRef(new HeartbeatActor(fakeConfig))
      fsm ! MessageActivate(FakeReactor, FakeSessionToken)
      fsm.stateName should be (StateActive)
      fsm.stateData should be (DataActive(FakeReactor, FakeSessionToken))
    }

    "reset missed upon receipt of a pulse message" in {
      val fsm = TestFSMRef(new HeartbeatActor(fakeConfig))
      fsm.setState(stateName = StateActive, stateData = DataActive(FakeReactor, FakeSessionToken, missed = 1))
      fsm ! MessagePulse
      fsm.stateName should be (StateActive)
      fsm.stateData should be (DataActive(FakeReactor, FakeSessionToken))
    }

    "reactivate when active, upon receipt of a valid activation message" in {
      val fsm = TestFSMRef(new HeartbeatActor(fakeConfig))
      fsm.setState(stateName = StateActive, stateData = DataActive(FakeReactor, FakeSessionToken, missed = 1))
      fsm ! MessageActivate(FakeReactor, AnotherFakeSessionToken)
      fsm.stateName should be (StateActive)
      fsm.stateData should be (DataActive(FakeReactor, AnotherFakeSessionToken))
    }

    "deactivate when active, upon receipt of a valid deactivation message" in {
      val fsm = TestFSMRef(new HeartbeatActor(fakeConfig))
      fsm.setState(stateName = StateActive, stateData = DataActive(FakeReactor, FakeSessionToken))
      fsm ! MessageDeactivate(FakeSessionToken)
      fsm.stateName should be (StateInactive)
      fsm.stateData should be (DataNone)
    }

    "do NOT deactivate when active, upon receipt of an invalid deactivation message" in {
      val fsm = TestFSMRef(new HeartbeatActor(fakeConfig))
      fsm.setState(stateName = StateActive, stateData = DataActive(FakeReactor, FakeSessionToken, missed = 1))
      fsm ! MessageDeactivate(InvalidSessionToken)
      fsm.stateName should be (StateActive)
      fsm.stateData should be (DataActive(FakeReactor, FakeSessionToken, missed = 1))
    }

    // ActorReactor translates Reactor callbacks into messages delivered to testActor (from ImplicitSender),
    // allows us to use things like "expectMsg" for simpler test cases.
    class ActorReactor extends Reactor {
      def onSkip(skipped: Int): Unit = testActor ! Skipped
      def onFailure(): Unit = testActor ! Failure
    }

    case object SkipDecorated
    case object FailureDecorated

    lazy val fakeReactorDecorator = Reactor.Decorator { r =>
      new Reactor {
        def onSkip(skipped: Int): Unit = {
          testActor ! SkipDecorated
          r.onSkip(skipped)
        }
        def onFailure(): Unit = {
          testActor ! FailureDecorated
          r.onFailure
        }
      }
    }

    // this variant of fake-config has a decorator impl that sends unique "decorator" messages, the goal
    // being to test the Config.withReactor implementation
    lazy val fakeConfig2 = fakeConfig.copy(reactorDecorator = Some(fakeReactorDecorator))

    "trigger onSkip upon receipt of a timeout when missed is less than failure threshold " in {
      val fsm = TestFSMRef(new HeartbeatActor(fakeConfig2))
      val reactor = fakeConfig2.withReactor(new ActorReactor)
      fsm.setState(stateName = StateActive, stateData = DataActive(reactor, FakeSessionToken))
      fsm ! FSM.StateTimeout
      expectMsg(SkipDecorated)
      expectMsg(Skipped)
      fsm.stateName should be (StateActive)
      fsm.stateData should be (DataActive(reactor, FakeSessionToken, missed = 1))
    }

    "trigger onFailure upon receipt of a timeout when missed is NOT less than failure threshold " in {
      val fsm = TestFSMRef(new HeartbeatActor(fakeConfig2))
      val reactor = fakeConfig2.withReactor(new ActorReactor)
      fsm.setState(stateName = StateActive, stateData = DataActive(reactor, FakeSessionToken, missed = 1))
      fsm ! FSM.StateTimeout
      expectMsg(FailureDecorated)
      expectMsg(Failure)
      fsm.stateName should be (StateInactive)
      fsm.stateData should be (DataNone)
    }

  }
}

object HeartbeatActorTest {
  import Heartbeat._

  case object FakeReactor extends Reactor {
    def onSkip(skipped: Int): Unit = ???
    def onFailure(): Unit = ???
  }

  case object FakeSessionToken
  case object AnotherFakeSessionToken
  case object InvalidSessionToken

  sealed trait Callback
  case object Skipped extends Callback
  case object Failure extends Callback

}
