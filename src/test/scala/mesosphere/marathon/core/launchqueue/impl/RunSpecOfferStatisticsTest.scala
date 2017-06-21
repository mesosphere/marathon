package mesosphere.marathon
package core.launchqueue.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.launcher.InstanceOp
import mesosphere.marathon.core.launcher.OfferMatchResult.{ Match, NoMatch }
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.NoOfferMatchReason
import mesosphere.mesos.NoOfferMatchReason._

class RunSpecOfferStatisticsTest extends UnitTest {

  "RunSpecOfferStatisticsTest" should {
    "Accumulate resource reasons for NoMatch with ResourceReasons" in {
      checkNoMatch(
        reasons = Seq(InsufficientCpus, InsufficientGpus, InsufficientDisk, InsufficientPorts),
        expectedIncrements = Seq(InsufficientCpus)
      )
    }

    "Accumulate resource reasons for NoMatch with ResourceReasons and UnmatchedConstraint" in {
      checkNoMatch(
        reasons = Seq(InsufficientCpus, InsufficientGpus, InsufficientDisk, UnfulfilledConstraint),
        expectedIncrements = Seq(UnfulfilledConstraint)
      )
    }

    "Accumulate resource reasons for NoMatch with ResourceReasons and UnmatchedConstraint and UnmatchedRole" in {
      checkNoMatch(
        reasons = Seq(InsufficientCpus, InsufficientGpus, InsufficientDisk, UnfulfilledRole, UnfulfilledConstraint),
        expectedIncrements = Seq(UnfulfilledRole)
      )
    }

    "Accumulate resource reasons for Match" in {
      Given("Empty statistics")
      val f = new Fixture
      val statistics = f.emptyStatistics

      When("A Match is processed")
      val updated = statistics.incrementMatched(f.matched)

      Then("The relevant counters are updated")
      updated.lastMatch should be(Some(f.matched))
      updated.processedOfferCount should be(1)
      updated.unusedOfferCount should be(0)
      updated.rejectSummary.size should be(0)
    }
  }
  /**
    * The set of reasons is applied to an empty statistics.
    * It should increment the expectedIncrements.
    * Please see [[OfferMatchStatisticsActor.RunSpecOfferStatistics.incrementUnmatched]] for this logic.
    * @param reasons all reasons to not use the offer
    * @param expectedIncrements the reasons that are expected in the summary
    */
  def checkNoMatch(reasons: Seq[NoOfferMatchReason], expectedIncrements: Seq[NoOfferMatchReason]): Unit = {
    Given("Empty statistics")
    val f = new Fixture
    val statistics = f.emptyStatistics
    val noMatch = NoMatch(f.runSpec, f.offer, reasons, Timestamp.now())

    When(s"A NoMatch is processed with ${reasons.mkString(", ")}")
    val updated = statistics.incrementUnmatched(noMatch)

    Then(s"All reasons are set accordingly with ${expectedIncrements.mkString(", ")}")
    updated.lastNoMatch should be(Some(noMatch))
    updated.processedOfferCount should be(1)
    updated.unusedOfferCount should be(1)
    updated.rejectSummary.size should be(expectedIncrements.size)
    expectedIncrements.foreach { reason => updated.rejectSummary(reason) should be(1) }
  }

  class Fixture {
    val emptyStatistics = OfferMatchStatisticsActor.emptyStatistics
    val runSpec = AppDefinition(PathId("/foo"))
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val instanceOp = mock[InstanceOp]
    val matched = Match(runSpec, offer, instanceOp, Timestamp.now())
  }
}
