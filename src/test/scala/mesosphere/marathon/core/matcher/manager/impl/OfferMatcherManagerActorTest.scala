package mesosphere.marathon
package core.matcher.manager.impl

import java.util
import java.util.concurrent.TimeUnit

import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout
import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.base.util.ActorOfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerConfig
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.Protos.Offer
import org.rogach.scallop.ScallopConf
import org.scalatest.concurrent.Eventually
import rx.lang.scala.Observer

import scala.concurrent.{ Future, Promise }
import scala.util.{ Random, Success }
import scala.concurrent.duration._

class OfferMatcherManagerActorTest extends AkkaUnitTest with Eventually {

  "OfferMatcherManagerActor" should {
    "The list of OfferMatchers is random without precedence" in {
      Given("OfferMatcher with num normal matchers")
      val num = 5
      val f = new Fixture
      val appId = PathId("/some/app")
      val manager = f.offerMatcherManager
      val matchers = 1.to(num).map(_ => f.matcher())
      matchers.map { matcher => manager ? OfferMatcherManagerDelegate.AddOrUpdateMatcher(matcher) }

      When("The list of offer matchers is fetched")
      val orderedMatchers = manager.underlyingActor.offerMatchers(f.reservedOffer(appId))

      Then("The list is sorted in the correct order")
      orderedMatchers should have size num.toLong
      orderedMatchers should contain theSameElementsAs matchers
    }

    "The list of OfferMatchers is sorted by precedence" in {
      Given("OfferMatcher with num precedence and num normal matchers, registered in mixed order")
      val num = 5
      val f = new Fixture
      val appId = PathId("/some/app")
      val manager = f.offerMatcherManager
      1.to(num).flatMap(_ => Seq(f.matcher(), f.matcher(Some(appId)))).map { matcher =>
        manager ? OfferMatcherManagerDelegate.AddOrUpdateMatcher(matcher)
      }

      When("The list of offer matchers is fetched")
      val sortedMatchers = manager.underlyingActor.offerMatchers(f.reservedOffer(appId))

      Then("The list is sorted in the correct order")
      sortedMatchers should have size 2 * num.toLong
      val (left, right) = sortedMatchers.splitAt(num)
      left.count(_.precedenceFor.isDefined) should be(num)
      right.count(_.precedenceFor.isDefined) should be(0)
    }

    "queue offers, if the maximum number of offer matchers is busy" in new Fixture {
      Given("OfferMatcher with one matcher")
      val offerMatch1 = Promise[OfferMatcher.MatchedInstanceOps]
      val offerMatch2 = Promise[OfferMatcher.MatchedInstanceOps]
      val offer1 = offer()
      val offer2 = offer()
      val offerPass1 = Promise[OfferMatcher.MatchedInstanceOps]
      val offerPass2 = Promise[OfferMatcher.MatchedInstanceOps]
      offerMatcherManager.underlyingActor.launchTokens = 100
      val offerPass = Map(offer1 -> offerPass1.future, offer2 -> offerPass2.future)
      offerMatcherManager ? OfferMatcherManagerDelegate.AddOrUpdateMatcher(matcherWith(offerPass))

      When("one offer is send to the manager")
      offerMatcherManager ! ActorOfferMatcher.MatchOffer(offer1, offerMatch1)

      Then("The offer is not queued")
      offerMatcherManager.underlyingActor.unprocessedOffers should have size 0

      When("another offer is send to the manager")
      offerMatcherManager ! ActorOfferMatcher.MatchOffer(offer2, offerMatch2)

      Then("One offer should be queued, since all matchers are busy")
      eventually(offerMatcherManager.underlyingActor.unprocessedOffers should have size 1)

      When("The offer is matched")
      offerPass1.complete(Success(OfferMatcher.MatchedInstanceOps(offer1.getId, Seq.empty)))
      offerPass2.complete(Success(OfferMatcher.MatchedInstanceOps(offer2.getId, Seq.empty)))

      Then("The queued offer is taken")
      eventually(offerMatcherManager.underlyingActor.unprocessedOffers should have size 0)

      And("The promise should be fullfilled")
      offerMatch1.future.futureValue.opsWithSource should be('empty)
      offerMatch2.future.futureValue.opsWithSource should be('empty)
    }

    "decline offers immediately, if nobody is interested in offers" in new Fixture {
      Given("OfferMatcher with one matcher")
      val offerMatch1 = Promise[OfferMatcher.MatchedInstanceOps]
      val offerMatch2 = Promise[OfferMatcher.MatchedInstanceOps]
      val offer1 = offer()
      val offer2 = offer()
      offerMatcherManager.underlyingActor.launchTokens = 100

      When("2 offers are send to the manager")
      offerMatcherManager ! ActorOfferMatcher.MatchOffer(offer1, offerMatch1)
      offerMatcherManager ! ActorOfferMatcher.MatchOffer(offer2, offerMatch2)

      Then("One offer is declined immediately")
      offerMatch1.future.futureValue.resendThisOffer should be(false)
      offerMatch2.future.futureValue.resendThisOffer should be(false)
    }

    "decline offers immediately, if the maximum number of offer matchers is busy and the offers queue is full" in new Fixture {
      Given("OfferMatcher with one matcher")
      val offerMatch1 = Promise[OfferMatcher.MatchedInstanceOps]
      val offerMatch2 = Promise[OfferMatcher.MatchedInstanceOps]
      val offerMatch3 = Promise[OfferMatcher.MatchedInstanceOps]
      val offer1 = offer()
      val offer2 = offer()
      val offer3 = offer()
      val offerPass1 = Promise[OfferMatcher.MatchedInstanceOps]
      val offerPass2 = Promise[OfferMatcher.MatchedInstanceOps]
      offerMatcherManager.underlyingActor.launchTokens = 100
      val offerPass = Map(offer1 -> offerPass1.future, offer2 -> offerPass2.future)
      offerMatcherManager ? OfferMatcherManagerDelegate.AddOrUpdateMatcher(matcherWith(offerPass))

      When("2 offers are send to the manager")
      offerMatcherManager ! ActorOfferMatcher.MatchOffer(offer1, offerMatch1)
      offerMatcherManager ! ActorOfferMatcher.MatchOffer(offer2, offerMatch2)

      Then("One offer is matched and one should be queued")
      eventually(offerMatcherManager.underlyingActor.unprocessedOffers should have size 1)

      When("Another offer is send")
      offerMatcherManager ! ActorOfferMatcher.MatchOffer(offer3, offerMatch3)

      Then("The offer is declined immediately")
      offerMatch3.future.futureValue.resendThisOffer should be(true)
      offerMatch1.isCompleted should be(false)
      offerMatch2.isCompleted should be(false)

      And("If the matcher passes matching, the resulting promise should be fulfilled")
      offerPass1.complete(Success(OfferMatcher.MatchedInstanceOps(offer1.getId, Seq.empty)))
      offerPass2.complete(Success(OfferMatcher.MatchedInstanceOps(offer2.getId, Seq.empty)))
      offerMatch1.future.futureValue.opsWithSource should be('empty)
      offerMatch2.future.futureValue.opsWithSource should be('empty)
    }

    "overdue offers are rejected after the deadline" in new Fixture(Seq("--max_parallel_offers", "1", "--max_queued_offers", "100", "--offer_matching_timeout", "10")) {
      Given("OfferMatcher with one matcher")
      val offer1 = offer()
      val offer2 = offer()
      val offer3 = offer()
      val offerMatch1 = Promise[OfferMatcher.MatchedInstanceOps]
      val offerMatch2 = Promise[OfferMatcher.MatchedInstanceOps]
      val offerMatch3 = Promise[OfferMatcher.MatchedInstanceOps]
      offerMatcherManager.underlyingActor.launchTokens = 100
      offerMatcherManager.underlyingActor.matchers += matcher()

      When("1 offer is send, which is passed to the matcher, 2 offers are send and queued with a 10 millis gap")
      offerMatcherManager ! ActorOfferMatcher.MatchOffer(offer1, offerMatch1)
      offerMatcherManager ! ActorOfferMatcher.MatchOffer(offer2, offerMatch2)
      clock += 10.millis
      offerMatcherManager ! ActorOfferMatcher.MatchOffer(offer3, offerMatch3)

      Then("offer-2 is declined, due to timeout but not offer-3")
      offerMatch2.future.futureValue.opsWithSource should be('empty)
      offerMatch3.isCompleted should be(false)
      offerMatch1.isCompleted should be(false)

      And("After 10 millis also offer-2 is declined")
      clock += 10.millis
      offerMatch3.future.futureValue.opsWithSource should be('empty)
      offerMatch1.isCompleted should be(false)
    }

    "offers are rejected if the matcher does not respond in time" in new Fixture(Seq("--max_parallel_offers", "1", "--max_queued_offers", "100", "--offer_matching_timeout", "10")) {
      Given("OfferMatcher with one matcher")
      val offer1 = offer()
      val offerMatch1 = Promise[OfferMatcher.MatchedInstanceOps]
      offerMatcherManager.underlyingActor.launchTokens = 100
      offerMatcherManager.underlyingActor.matchers += matcher()

      When("1 offer is send, which is passed to the matcher, but the matcher does not respond")
      offerMatcherManager ! ActorOfferMatcher.MatchOffer(offer1, offerMatch1)
      clock += 30.millis

      Then("offer-1 is declined, since the actor did not respond in time")
      offerMatch1.future.futureValue.opsWithSource should be('empty)
    }
  }

  implicit val timeout = Timeout(3, TimeUnit.SECONDS)
  class Fixture(config: Seq[String] = Seq("--max_parallel_offers", "1", "--max_queued_offers", "1")) {
    val metrics = new OfferMatcherManagerActorMetrics()
    val random = new Random(new util.Random())
    val idGen = 1.to(Int.MaxValue).iterator
    val clock = new SettableClock()
    val observer = Observer.apply[Boolean]((a: Boolean) => ())
    object Config extends ScallopConf(config) with OfferMatcherManagerConfig {
      verify()
    }
    val offerMatcherManager = TestActorRef[OfferMatcherManagerActor](OfferMatcherManagerActor.props(metrics, random, clock, Config, observer))

    def matcher(precedence: Option[PathId] = None): OfferMatcher = {
      val matcher = mock[OfferMatcher]
      val promise = Promise[OfferMatcher.MatchedInstanceOps]
      matcher.precedenceFor returns precedence
      matcher.matchOffer(any) returns promise.future
      matcher
    }

    def matcherWith(fn: Offer => Future[OfferMatcher.MatchedInstanceOps]): OfferMatcher = {
      val matcher = mock[OfferMatcher]
      matcher.precedenceFor returns None
      matcher.matchOffer(any) answers {
        case Array(offer: Offer) => fn(offer)
      }
      matcher
    }

    def offer(): Offer = MarathonTestHelper.makeBasicOffer().setId(org.apache.mesos.Protos.OfferID.newBuilder().setValue("offer-" + idGen.next())).build()
    def reservedOffer(appId: PathId, path: String = "test"): Offer = {
      import MarathonTestHelper._
      makeBasicOffer().addResources(reservedDisk(LocalVolumeId(appId, path, "uuid").idString, containerPath = path)).build()
    }
  }
}
