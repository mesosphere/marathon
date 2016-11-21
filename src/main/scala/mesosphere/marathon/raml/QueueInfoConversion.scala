package mesosphere.marathon
package raml

import java.time.OffsetDateTime

import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfoWithStatistics
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.AppDefinition
import mesosphere.mesos.NoOfferMatchReason

trait QueueInfoConversion extends DefaultConversions with OfferConversion {

  implicit val rejectReasonWrites: Writes[NoOfferMatchReason, String] = Writes { _.toString }

  implicit val unusedOfferWrites: Writes[OfferMatchResult.NoMatch, UnusedOffer] = Writes { noMatch =>
    UnusedOffer(Raml.toRaml(noMatch.offer), Raml.toRaml(noMatch.reasons), noMatch.timestamp.toOffsetDateTime)
  }

  implicit val queueInfoWithStatisticsWrites: Writes[(QueuedInstanceInfoWithStatistics, Boolean, Clock), QueueItem] = Writes {
    case (info, withLastUnused, clock) =>
      def delay: Option[QueueDelay] = {
        val timeLeft = clock.now() until info.backOffUntil
        val overdue = timeLeft.toSeconds < 0
        Some(QueueDelay(math.max(0, timeLeft.toSeconds), overdue = overdue))
      }

      def processedOffersSummary: ProcessedOffersSummary = {
        // `rejectSummaryLastOffers` should be a triple of (reason, amount declined, amount processed)
        // and should reflect the `NoOfferMatchReason.reasonFunnel` to store only first non matching reason.
        val (_, rejectSummaryLastOffers) = NoOfferMatchReason.
          reasonFunnel.foldLeft((info.lastProcessedOffersCount, Seq.empty[LastOfferRejectionSummary])) {
            case ((processed: Int, seq: Seq[LastOfferRejectionSummary]), reason: NoOfferMatchReason) =>
              val nextProcessed = processed - info.rejectSummaryLastOffers.getOrElse(reason, 0)
              (nextProcessed, seq :+ LastOfferRejectionSummary(reason.toString, info.rejectSummaryLastOffers.getOrElse(reason, 0), processed))
          }
        ProcessedOffersSummary(
          processedOffersCount = info.processedOffersCount,
          unusedOffersCount = info.unusedOffersCount,
          lastUnusedOfferAt = info.lastNoMatch.map(_.timestamp.toOffsetDateTime),
          lastUsedOfferAt = info.lastMatch.map(_.timestamp.toOffsetDateTime),
          rejectSummaryLastOffers = rejectSummaryLastOffers,
          rejectSummaryLaunchAttempt = Raml.toRaml(info.rejectSummaryLaunchAttempt)
        )
      }

      def queueItem[A](create: (Int, Option[QueueDelay], OffsetDateTime, ProcessedOffersSummary, Option[Seq[UnusedOffer]]) => A): A = {
        create(
          info.instancesLeftToLaunch,
          delay,
          info.startedAt.toOffsetDateTime,
          processedOffersSummary,
          if (withLastUnused) Some(Raml.toRaml(info.lastNoMatches)) else None
        )
      }

      info.runSpec match {
        case app: AppDefinition => queueItem(QueueApp(_, _, _, _, _, Raml.toRaml(app)))
        case pod: PodDefinition => queueItem(QueuePod(_, _, _, _, _, Raml.toRaml(pod)))
      }
  }

  implicit val queueWrites: Writes[(Seq[QueuedInstanceInfoWithStatistics], Boolean, Clock), Queue] = Writes {
    case (infos, withLastUnused, clock) =>
      Queue(infos.map(info => Raml.toRaml((info, withLastUnused, clock))))
  }
}
