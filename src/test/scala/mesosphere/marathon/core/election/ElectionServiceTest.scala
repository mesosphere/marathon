package mesosphere.marathon
package core.election

import akka.Done
import akka.actor.Cancellable
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Keep, Sink, Source }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.stream.Repeater
import org.scalatest.concurrent.Eventually
import mesosphere.marathon.test.TestCrashStrategy

class ElectionServiceTest extends AkkaUnitTest with Eventually {
  trait Fixture {
    val (input, output) = Source.queue[LeadershipState](32, OverflowStrategy.fail)
      .toMat(Repeater.sink(32, OverflowStrategy.fail))(Keep.both)
      .run

    @volatile var subscribed = false
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

    val electionService = new ElectionServiceImpl(
      "127.0.0.1:2015",
      leaderEvents,
      crashStrategy)(system)
    eventually { subscribed shouldBe true }

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
    val transitionEvents = electionService.leaderTransitionEvents.runWith(Sink.seq)
    input.offer(LeadershipState.Standby(Some("1")))
    input.offer(LeadershipState.Standby(None))
    input.offer(LeadershipState.Standby(Some("2")))
    input.complete()
    transitionEvents.futureValue shouldBe Nil
  }

  "it yields a LeadershipTransition event when going from standby to leader" in new Fixture {
    val transitionEvent = electionService.leaderTransitionEvents.runWith(Sink.head)
    input.offer(LeadershipState.Standby(None))
    input.offer(LeadershipState.ElectedAsLeader)
    transitionEvent.futureValue shouldBe LeadershipTransition.ObtainedLeadership
  }

  "it provides the last leadershipEvent published for streams showing up late" in new Fixture {
    val transitionEvent = electionService.leaderTransitionEvents.runWith(Sink.head)
    input.offer(LeadershipState.Standby(None))
    input.offer(LeadershipState.ElectedAsLeader)
    transitionEvent.futureValue shouldBe LeadershipTransition.ObtainedLeadership

    val lostEvent = electionService.leaderTransitionEvents.take(2).runWith(Sink.seq)
    input.offer(LeadershipState.Standby(None))
    lostEvent.futureValue shouldBe Seq(LeadershipTransition.ObtainedLeadership, LeadershipTransition.LostLeadership)
    electionService.leaderTransitionEvents.runWith(Sink.head).futureValue shouldBe (LeadershipTransition.LostLeadership)
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
