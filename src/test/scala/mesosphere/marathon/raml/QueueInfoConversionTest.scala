package mesosphere.marathon
package raml

import mesosphere.FunTest
import mesosphere.marathon.core.base.ConstantClock
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfoWithStatistics
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.NoOfferMatchReason

class QueueInfoConversionTest extends FunTest {

  test("A reject reason is converted correctly") {
    Given("A reject reason")
    val reason = NoOfferMatchReason.InsufficientCpus

    When("The value is converted to raml")
    val raml = reason.toRaml[String]

    Then("The value is converted correctly")
    raml should be (reason.toString)
  }

  test("A NoMatch is converted correctly") {
    Given("A NoMatch")
    val app = AppDefinition(PathId("/test"))
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val noMatch = OfferMatchResult.NoMatch(app, offer, Seq(NoOfferMatchReason.InsufficientCpus), Timestamp.now())

    When("The value is converted to raml")
    val raml = noMatch.toRaml[UnusedOffer]

    Then("The value is converted correctly")
    raml.offer should be (offer.toRaml[Offer])
    raml.reason should be(noMatch.reasons.toRaml[Seq[String]])
    raml.timestamp should be (noMatch.timestamp.toOffsetDateTime)
  }

  test("A QueueInfoWithStatistics is converted correctly") {
    Given("A QueueInfoWithStatistics")
    val clock = ConstantClock()
    val now = clock.now()
    val app = AppDefinition(PathId("/test"))
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val noMatch = OfferMatchResult.NoMatch(app, offer, Seq(NoOfferMatchReason.InsufficientCpus), now)
    val summary: Map[NoOfferMatchReason, Int] = Map(NoOfferMatchReason.InsufficientCpus -> 75, NoOfferMatchReason.InsufficientMemory -> 15, NoOfferMatchReason.InsufficientDisk -> 10)
    val lastOffersSummary: Seq[LastOfferRejectionSummary] = List(
      LastOfferRejectionSummary("UnfulfilledRole", 0, 123),
      LastOfferRejectionSummary("UnfulfilledConstraint", 0, 123),
      LastOfferRejectionSummary("NoCorrespondingReservationFound", 0, 123),
      LastOfferRejectionSummary("InsufficientCpus", 75, 123), // 123 - 75 = 48
      LastOfferRejectionSummary("InsufficientMemory", 15, 48), // 48 - 15 = 33
      LastOfferRejectionSummary("InsufficientDisk", 10, 33), // 33 - 10 = 23
      LastOfferRejectionSummary("InsufficientGpus", 0, 23),
      LastOfferRejectionSummary("InsufficientPorts", 0, 23)
    )

    val info = QueuedInstanceInfoWithStatistics(app, inProgress = true,
      instancesLeftToLaunch = 23,
      finalInstanceCount = 23,
      unreachableInstances = 12,
      backOffUntil = now,
      startedAt = now,
      rejectSummaryLastOffers = summary,
      rejectSummaryLaunchAttempt = summary,
      processedOffersCount = 123,
      lastProcessedOffersCount = 123,
      unusedOffersCount = 100,
      lastMatch = None,
      lastNoMatch = Some(noMatch),
      lastNoMatches = Seq(noMatch))

    When("The value is converted to raml")
    val raml = (Seq(info), true, clock).toRaml[Queue]

    Then("The value is converted correctly")
    raml.queue should have size 1
    raml.queue.head shouldBe a[QueueApp]
    val item = raml.queue.head.asInstanceOf[QueueApp]
    item.app.id should be (app.id.toString)
    item.count should be(23)
    item.processedOffersSummary.processedOffersCount should be(info.processedOffersCount)
    item.processedOffersSummary.unusedOffersCount should be(info.unusedOffersCount)
    item.processedOffersSummary.lastUnusedOfferAt should be(Some(now.toOffsetDateTime))
    item.processedOffersSummary.lastUsedOfferAt should be(None)
    item.processedOffersSummary.rejectSummaryLaunchAttempt should be(summary.toRaml[Map[String, Int]])
    item.processedOffersSummary.rejectSummaryLastOffers should be(lastOffersSummary)
    item.lastUnusedOffers should be (defined)
    item.since should be(now.toOffsetDateTime)
  }
}
