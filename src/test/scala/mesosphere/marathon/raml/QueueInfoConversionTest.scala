package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.test.SettableClock
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfoWithStatistics
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp }
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.NoOfferMatchReason

class QueueInfoConversionTest extends UnitTest {

  "QueueInfoConversion" should {
    "A reject reason is converted correctly" in {
      Given("A reject reason")
      val reason = NoOfferMatchReason.InsufficientCpus

      When("The value is converted to raml")
      val raml = reason.toRaml[String]

      Then("The value is converted correctly")
      raml should be (reason.toString)
    }

    "A NoMatch is converted correctly" in {
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

    "A QueueInfoWithStatistics is converted correctly" in {
      Given("A QueueInfoWithStatistics")
      val clock = new SettableClock()
      val now = clock.now()
      val app = AppDefinition(PathId("/test"))
      val offer = MarathonTestHelper.makeBasicOffer().build()
      val noMatch = Seq(
        OfferMatchResult.NoMatch(app, offer, Seq(NoOfferMatchReason.InsufficientCpus), now),
        OfferMatchResult.NoMatch(app, offer, Seq(NoOfferMatchReason.InsufficientCpus), now),
        OfferMatchResult.NoMatch(app, offer, Seq(NoOfferMatchReason.InsufficientCpus), now),
        OfferMatchResult.NoMatch(app, offer, Seq(NoOfferMatchReason.InsufficientMemory), now)
      )
      val summary: Map[NoOfferMatchReason, Int] = Map(NoOfferMatchReason.InsufficientCpus -> 75, NoOfferMatchReason.InsufficientMemory -> 15, NoOfferMatchReason.InsufficientDisk -> 10)
      val lastSummary: Map[NoOfferMatchReason, Int] = Map(NoOfferMatchReason.InsufficientCpus -> 3, NoOfferMatchReason.InsufficientMemory -> 1)
      val offersSummary: Seq[DeclinedOfferStep] = List(
        DeclinedOfferStep("UnfulfilledRole", 0, 123),
        DeclinedOfferStep("UnfulfilledConstraint", 0, 123),
        DeclinedOfferStep("NoCorrespondingReservationFound", 0, 123),
        DeclinedOfferStep("InsufficientCpus", 75, 123), // 123 - 75 = 48
        DeclinedOfferStep("InsufficientMemory", 15, 48), // 48 - 15 = 33
        DeclinedOfferStep("InsufficientDisk", 10, 33), // 33 - 10 = 23
        DeclinedOfferStep("InsufficientGpus", 0, 23),
        DeclinedOfferStep("InsufficientPorts", 0, 23)
      )
      val lastOffersSummary: Seq[DeclinedOfferStep] = List(
        DeclinedOfferStep("UnfulfilledRole", 0, 4),
        DeclinedOfferStep("UnfulfilledConstraint", 0, 4),
        DeclinedOfferStep("NoCorrespondingReservationFound", 0, 4),
        DeclinedOfferStep("InsufficientCpus", 3, 4), // 4 - 3 = 1
        DeclinedOfferStep("InsufficientMemory", 1, 1), // 1 - 1 = 0
        DeclinedOfferStep("InsufficientDisk", 0, 0),
        DeclinedOfferStep("InsufficientGpus", 0, 0),
        DeclinedOfferStep("InsufficientPorts", 0, 0)
      )

      val info = QueuedInstanceInfoWithStatistics(app, inProgress = true,
        instancesLeftToLaunch = 23,
        finalInstanceCount = 23,
        backOffUntil = now,
        startedAt = now,
        rejectSummaryLastOffers = lastSummary,
        rejectSummaryLaunchAttempt = summary,
        processedOffersCount = 123,
        unusedOffersCount = 100,
        lastMatch = None,
        lastNoMatch = Some(noMatch.head),
        lastNoMatches = noMatch)

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
      item.processedOffersSummary.rejectSummaryLaunchAttempt should be(offersSummary)
      item.processedOffersSummary.rejectSummaryLastOffers should be(lastOffersSummary)
      item.lastUnusedOffers should be (defined)
      item.since should be(now.toOffsetDateTime)
    }
  }
}
