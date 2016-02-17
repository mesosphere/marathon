package mesosphere.marathon.core.matcher.manager.impl

import java.util
import java.util.concurrent.TimeUnit

import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout
import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.matcher.base.OfferMatcher
import mesosphere.marathon.core.matcher.manager.OfferMatcherManagerConfig
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.{ MarathonActorSupport, Mockito }
import org.apache.mesos.Protos.Offer
import org.rogach.scallop.ScallopConf
import org.scalatest.{ FunSuiteLike, GivenWhenThen, Matchers }
import rx.lang.scala.Observer

import scala.util.Random

class OfferMatcherManagerActorTest extends MarathonActorSupport with FunSuiteLike with Matchers with GivenWhenThen with Mockito {

  test("The list of OfferMatchers is random without precedence") {
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

  test("The list of OfferMatchers is sorted by precedence") {
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
    left.count(_.precedenceFor.isDefined) should be (num)
    right.count(_.precedenceFor.isDefined) should be (0)
  }

  implicit val timeout = Timeout(3, TimeUnit.SECONDS)
  class Fixture {
    val metricRegistry = mock[Metrics]
    val metrics = new OfferMatcherManagerActorMetrics(metricRegistry)
    val random = new Random(new util.Random())
    val clock = ConstantClock()
    val observer = Observer.apply[Boolean]((a: Boolean) => ())
    object Config extends ScallopConf with OfferMatcherManagerConfig
    Config.afterInit()
    val offerMatcherManager = TestActorRef[OfferMatcherManagerActor](OfferMatcherManagerActor.props(metrics, random, clock, Config, observer))

    def matcher(precedence: Option[PathId] = None): OfferMatcher = {
      val matcher = mock[OfferMatcher]
      matcher.precedenceFor returns precedence
      matcher
    }

    def reservedOffer(appId: PathId, path: String = "test"): Offer = {
      import MarathonTestHelper._
      makeBasicOffer().addResources(reservedDisk(LocalVolumeId(appId, path, "uuid").idString, containerPath = path)).build()
    }
  }
}
