package mesosphere.marathon.core.matcher.base.util

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.OfferMatcher.{ TaskOpWithSource, MatchedTaskOps }
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.test.Mockito
import org.apache.mesos.Protos.Offer
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ Matchers, GivenWhenThen, FunSuite }
import org.apache.mesos.{ Protos => MesosProtos }

import scala.concurrent.Future
import scala.concurrent.duration._

class StopOnFirstMatchingOfferMatcherTest extends FunSuite with Mockito with GivenWhenThen with Matchers with ScalaFutures {
  test("returns first match if non-empty") {
    Given("a sequence of matchers, the first matching")
    val f = new Fixture {
      override lazy val matchers: Seq[OfferMatcher] = Seq(
        offerMatcher(someMatch),
        offerMatcher(OfferMatcher.MatchedTaskOps.noMatch(offer.getId))
      )
    }

    When("matching")
    val m = f.stopOnFirstMatching.matchOffer(f.deadline, f.offer).futureValue

    Then("the first match is returned")
    m should be(f.someMatch)
  }

  test("returns second match if first empty") {
    Given("a sequence of matchers, the second matching")
    val f = new Fixture {
      override lazy val matchers: Seq[OfferMatcher] = Seq(
        offerMatcher(OfferMatcher.MatchedTaskOps.noMatch(offer.getId)),
        offerMatcher(someMatch)
      )
    }

    When("matching")
    val m = f.stopOnFirstMatching.matchOffer(f.deadline, f.offer).futureValue

    Then("the second match is returned")
    m should be(f.someMatch)
  }

  test("returns last match if all empty (resend = false)") {
    Given("a sequence of matchers, the second matching")
    val f = new Fixture {
      override lazy val matchers: Seq[OfferMatcher] = Seq(
        offerMatcher(OfferMatcher.MatchedTaskOps.noMatch(offer.getId, resendThisOffer = true)),
        offerMatcher(OfferMatcher.MatchedTaskOps.noMatch(offer.getId, resendThisOffer = false))
      )
    }

    When("matching")
    val m = f.stopOnFirstMatching.matchOffer(f.deadline, f.offer).futureValue

    Then("the last match is returned")
    m.ops should be(empty)
    m.resendThisOffer should be(false)
  }

  test("returns last match if all empty (resend = true)") {
    Given("a sequence of matchers, the second matching")
    val f = new Fixture {
      override lazy val matchers: Seq[OfferMatcher] = Seq(
        offerMatcher(OfferMatcher.MatchedTaskOps.noMatch(offer.getId, resendThisOffer = false)),
        offerMatcher(OfferMatcher.MatchedTaskOps.noMatch(offer.getId, resendThisOffer = true))
      )
    }

    When("matching")
    val m = f.stopOnFirstMatching.matchOffer(f.deadline, f.offer).futureValue

    Then("the last match is returned")
    m.ops should be(empty)
    m.resendThisOffer should be(true)
  }

  class Fixture {
    lazy val offer: MesosProtos.Offer = MarathonTestHelper.makeBasicOffer().build()
    lazy val deadline = Timestamp.now() + 30.seconds

    lazy val someMatch: OfferMatcher.MatchedTaskOps = {
      MatchedTaskOps(
        offer.getId,
        Seq(mock[TaskOpWithSource]),
        resendThisOffer = true
      )
    }

    def offerMatcher(matching: OfferMatcher.MatchedTaskOps): OfferMatcher = new OfferMatcher {
      override def matchOffer(deadline: Timestamp, offer: Offer): Future[MatchedTaskOps] = {
        Future.successful(matching)
      }
    }

    lazy val matchers: Seq[OfferMatcher] = Seq.empty
    lazy val stopOnFirstMatching = new StopOnFirstMatchingOfferMatcher(matchers: _*)
  }
}
