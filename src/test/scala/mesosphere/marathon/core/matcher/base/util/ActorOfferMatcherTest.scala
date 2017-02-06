package mesosphere.marathon
package core.matcher.base.util

import akka.actor.ActorRef
import akka.testkit.TestActor.AutoPilot
import akka.testkit.{ TestActor, TestProbe }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedInstanceOps
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.OfferID

import scala.concurrent.Future
import scala.concurrent.duration._

class ActorOfferMatcherTest extends AkkaUnitTest {

  "The ActorOfferMatcher" when {
    "asking the actor" should {
      val now = Timestamp.zero
      val deadline = now + 5.minutes
      val probe = TestProbe()
      probe.setAutoPilot(new TestActor.AutoPilot {
        override def run(sender: ActorRef, msg: Any): AutoPilot = {
          msg match {
            case ActorOfferMatcher.MatchOffer(deadline, offer, p) =>
              p.trySuccess(MatchedInstanceOps(OfferID("other"), Seq.empty, true))
              TestActor.NoAutoPilot
            case _ =>
              TestActor.NoAutoPilot
          }
        }
      })
      val offer = MarathonTestHelper.makeBasicOffer().build()

      val offerMatcher = new ActorOfferMatcher(probe.ref, None)(scheduler)
      val offerMatch: MatchedInstanceOps = offerMatcher.matchOffer(now, deadline, offer).futureValue

      "find a match in time" in {
        offerMatch.offerId should not be (offer.getId)
        offerMatch.offerId.getValue should be("other")
      }
    }

    "the actor has no time to process" should {
      val now = Timestamp.zero
      val deadline = now + 1.milli

      val probe = TestProbe()
      probe.setAutoPilot(new TestActor.AutoPilot {
        override def run(sender: ActorRef, msg: Any): AutoPilot = {
          msg match {
            case ActorOfferMatcher.MatchOffer(deadline, offer, p) =>
              p.trySuccess(MatchedInstanceOps(OfferID("other"), Seq.empty, true))
              TestActor.NoAutoPilot
            case _ =>
              TestActor.NoAutoPilot
          }
        }
      })
      val offer = MarathonTestHelper.makeBasicOffer().build()
      val offerMatcher = new ActorOfferMatcher(probe.ref, None)(scheduler)
      val offerMatch: MatchedInstanceOps = offerMatcher.matchOffer(now, deadline, offer).futureValue

      "receive a no match immediately" in {
        offerMatch should be(MatchedInstanceOps.noMatch(offer.getId))
        probe.expectNoMsg()
      }
    }

    "the actor takes too long to process" should {
      val now = Timestamp.zero
      val deadline = now + 60.millis

      val probe = TestProbe()
      probe.setAutoPilot(new TestActor.AutoPilot {
        override def run(sender: ActorRef, msg: Any): AutoPilot = {
          msg match {
            case ActorOfferMatcher.MatchOffer(deadline, offer, p) =>
              // We have to run in another thread to abvoid blocking the test code.
              Future {
                Thread.sleep(100.millis.toMillis)
                p.trySuccess(MatchedInstanceOps(OfferID("other-2"), Seq.empty, true))
              }
              TestActor.NoAutoPilot
            case _ =>
              TestActor.NoAutoPilot
          }
        }
      })
      val offer = MarathonTestHelper.makeBasicOffer().build()

      val offerMatcher = new ActorOfferMatcher(probe.ref, None)(scheduler)
      val offerMatch: MatchedInstanceOps = offerMatcher.matchOffer(now, deadline, offer).futureValue

      "receive a no match after deadline" in {
        offerMatch should be(MatchedInstanceOps.noMatch(offer.getId))
      }
    }
  }
}
