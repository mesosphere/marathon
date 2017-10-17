package mesosphere.marathon
package core.matcher.base.util

import mesosphere.UnitTest
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ InstanceOpWithSource, MatchedInstanceOps }
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.Protos.Offer
import org.apache.mesos.{ Protos => MesosProtos }

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._

class StopOnFirstMatchingOfferMatcherTest extends UnitTest {

  "StopOnFirstOfferMatching" should {
    "returns first match if non-empty" in {
      Given("a sequence of matchers, the first matching")
      val f = new Fixture {
        override lazy val matchers: Seq[OfferMatcher] = Seq(
          offerMatcher(someMatch),
          offerMatcher(OfferMatcher.MatchedInstanceOps.noMatch(offer.getId))
        )
      }

      When("matching")
      val m = f.stopOnFirstMatching.matchOffer(f.offer).futureValue

      Then("the first match is returned")
      m should be(f.someMatch)
    }

    "returns second match if first empty" in {
      Given("a sequence of matchers, the second matching")
      val f = new Fixture {
        override lazy val matchers: Seq[OfferMatcher] = Seq(
          offerMatcher(OfferMatcher.MatchedInstanceOps.noMatch(offer.getId)),
          offerMatcher(someMatch)
        )
      }

      When("matching")
      val m = f.stopOnFirstMatching.matchOffer(f.offer).futureValue

      Then("the second match is returned")
      m should be(f.someMatch)
    }

    "returns last match if all empty (resend = false)" in {
      Given("a sequence of matchers, the second matching")
      val f = new Fixture {
        override lazy val matchers: Seq[OfferMatcher] = Seq(
          offerMatcher(OfferMatcher.MatchedInstanceOps.noMatch(offer.getId, resendThisOffer = true)),
          offerMatcher(OfferMatcher.MatchedInstanceOps.noMatch(offer.getId, resendThisOffer = false))
        )
      }

      When("matching")
      val m = f.stopOnFirstMatching.matchOffer(f.offer).futureValue

      Then("the last match is returned")
      m.ops should be(empty)
      m.resendThisOffer should be(false)
    }

    "returns last match if all empty (resend = true)" in {
      Given("a sequence of matchers, the second matching")
      val f = new Fixture {
        override lazy val matchers: Seq[OfferMatcher] = Seq(
          offerMatcher(OfferMatcher.MatchedInstanceOps.noMatch(offer.getId, resendThisOffer = false)),
          offerMatcher(OfferMatcher.MatchedInstanceOps.noMatch(offer.getId, resendThisOffer = true))
        )
      }

      When("matching")
      val m = f.stopOnFirstMatching.matchOffer(f.offer).futureValue

      Then("the last match is returned")
      m.ops should be(empty)
      m.resendThisOffer should be(true)
    }
  }
  class Fixture {
    lazy val offer: MesosProtos.Offer = MarathonTestHelper.makeBasicOffer().build()
    lazy val now = Timestamp.now()
    lazy val deadline = now + 30.seconds

    lazy val someMatch: OfferMatcher.MatchedInstanceOps = {
      MatchedInstanceOps(
        offer.getId,
        immutable.Seq(mock[InstanceOpWithSource]),
        resendThisOffer = true
      )
    }

    def offerMatcher(matching: OfferMatcher.MatchedInstanceOps): OfferMatcher = new OfferMatcher {
      override def matchOffer(offer: Offer): Future[MatchedInstanceOps] = {
        Future.successful(matching)
      }
    }

    lazy val matchers: Seq[OfferMatcher] = Seq.empty
    lazy val stopOnFirstMatching = new StopOnFirstMatchingOfferMatcher(matchers: _*)
  }
}
