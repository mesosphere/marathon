package mesosphere.marathon
package core.election

import akka.Done
import akka.actor.{ Cancellable, PoisonPill }
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ BroadcastHub, Keep, Sink, Source }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.util.CancellableOnce
import org.scalatest.concurrent.Eventually
import mesosphere.marathon.test.TestCrashStrategy
import scala.concurrent.duration._

class ElectionServiceTest extends AkkaUnitTest with Eventually {
  trait Fixture {

    @volatile var subscribed = false
    val (input, output) = Source.queue[LeadershipState](32, OverflowStrategy.fail)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run

    val crashStrategy = new TestCrashStrategy

    val leaderEvents = output.mapMaterializedValue { c =>
      subscribed = true
      new Cancellable {
        @volatile var cancelled = false
        override def cancel(): Boolean = {
          input.complete()
          cancelled = true
          true
        }
        override def isCancelled: Boolean = cancelled
      }
    }

    var leadershipStarted = false
    var leadershipStopped = false
    val stubElectionCandidate: ElectionCandidate = new ElectionCandidate {
      override def startLeadership(): Unit = { leadershipStarted = true }
      override def stopLeadership(): Unit = { leadershipStopped = true }
    }
    val electionService = new ElectionServiceImpl(
      system.eventStream,
      "127.0.0.1:2015",
      leaderEvents,
      crashStrategy,
      ctx
    )(system)
    electionService.offerLeadership(stubElectionCandidate)
    eventually { subscribed shouldBe true }
    val leaderTransitionEvents = electionService.leadershipTransitionEvents
  }

  "it calls crash if the stream fails" in new Fixture {
    input.fail(new RuntimeException("super fail"))
    eventually {
      crashStrategy.crashed shouldBe (true)
    }
  }

  "it calls suicide if we lose leadership" in new Fixture {
    input.offer(LeadershipState.ElectedAsLeader)
    input.offer(LeadershipState.Standby(None))
    eventually { crashStrategy.crashed shouldBe (true) }
  }

  "it calls suicide if the leader election source closes" in new Fixture {
    input.complete()
    eventually {
      crashStrategy.crashed shouldBe (true)
    }
  }

  "it does not yield a LeadershipTransition event when going from one leader to another" in new Fixture {
    val transitionEvents = leaderTransitionEvents.takeWithin(1.second).runWith(Sink.seq)

    input.offer(LeadershipState.Standby(Some("1")))
    input.offer(LeadershipState.Standby(None))
    input.offer(LeadershipState.Standby(Some("2")))
    input.complete()
    transitionEvents.futureValue shouldBe List(LeadershipTransition.Standby)
  }

  "it yields a LeadershipTransition event when going from standby to leader" in new Fixture {
    val transitionEvent = leaderTransitionEvents.take(2).runWith(Sink.seq)
    input.offer(LeadershipState.Standby(None))
    input.offer(LeadershipState.ElectedAsLeader)
    transitionEvent.futureValue shouldBe List(LeadershipTransition.Standby, LeadershipTransition.ElectedAsLeaderAndReady)
  }

  "it provides the last leadershipEvent published for subscribers showing up late" in new Fixture {
    val transitionEvent = leaderTransitionEvents.take(2).runWith(Sink.seq)
    input.offer(LeadershipState.Standby(None))
    input.offer(LeadershipState.ElectedAsLeader)
    transitionEvent.futureValue shouldBe List(LeadershipTransition.Standby, LeadershipTransition.ElectedAsLeaderAndReady)

    val lostEvent = leaderTransitionEvents.take(2).runWith(Sink.seq)
    input.offer(LeadershipState.Standby(None))
    lostEvent.futureValue shouldBe Seq(LeadershipTransition.ElectedAsLeaderAndReady, LeadershipTransition.Standby)
    leaderTransitionEvents.runWith(Sink.head).futureValue shouldBe (LeadershipTransition.Standby)
  }

  "it provides the current leader info" in new Fixture {
    input.offer(LeadershipState.Standby(Some("asdf:1000")))
    eventually { electionService.leaderHostPort shouldBe Some("asdf:1000") }
    input.offer(LeadershipState.ElectedAsLeader)
    eventually { electionService.leaderHostPort shouldBe Some(electionService.localHostPort) }
  }

  "it closes the leader stream gracefully and suicides on abdicate" in new Fixture {
    electionService.abdicateLeadership()
    input.watchCompletion().futureValue shouldBe (Done)
    eventually { crashStrategy.crashed shouldBe (true) }
  }
}
