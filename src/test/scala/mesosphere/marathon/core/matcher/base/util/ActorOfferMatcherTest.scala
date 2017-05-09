package mesosphere.marathon
package core.matcher.base.util

import akka.actor.ActorRef
import akka.testkit.TestActor.AutoPilot
import akka.testkit.{ TestActor, TestProbe }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.matcher.base.OfferMatcher.MatchedInstanceOps
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.OfferID

class ActorOfferMatcherTest extends AkkaUnitTest {
  "The ActorOfferMatcher" when {
    "asking the actor" should {
      "find a match in time" in {
        val probe = TestProbe()
        probe.setAutoPilot(new TestActor.AutoPilot {
          override def run(sender: ActorRef, msg: Any): AutoPilot = {
            msg match {
              case ActorOfferMatcher.MatchOffer(offer, p) =>
                p.trySuccess(MatchedInstanceOps(OfferID("other"), Seq.empty, true))
                TestActor.NoAutoPilot
              case _ =>
                TestActor.NoAutoPilot
            }
          }
        })
        val offer = MarathonTestHelper.makeBasicOffer().build()

        val offerMatcher = new ActorOfferMatcher(probe.ref, None)(scheduler)
        val offerMatch: MatchedInstanceOps = offerMatcher.matchOffer(offer).futureValue

        offerMatch.offerId should not be (offer.getId)
        offerMatch.offerId.getValue should be("other")
      }
    }
  }
}
