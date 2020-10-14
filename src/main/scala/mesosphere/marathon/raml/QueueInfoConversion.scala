package mesosphere.marathon
package raml

import java.time.Clock

import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.LaunchStats.QueuedInstanceInfoWithStatistics
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
      def delay: QueueDelay = info.queueDelay(clock)

      /*
       *  `rejectSummaryLastOffers` should be a triple of (reason, amount declined, amount processed)
       * and should reflect the `NoOfferMatchReason.reasonFunnel` to store only first non matching reason.
       *
        * @param processedOffers the amount of last processed offers
       * @param summary the summary about the last processed offers
       * @return calculated Seq of `DeclinedOfferStep`
       */
      def declinedOfferSteps(processedOffers: Int, summary: Map[NoOfferMatchReason, Int]): Seq[DeclinedOfferStep] = {
        val (_, rejectSummaryLastOffers) = NoOfferMatchReason.reasonFunnel.foldLeft((processedOffers, Seq.empty[DeclinedOfferStep])) {
          case ((processed: Int, seq: Seq[DeclinedOfferStep]), reason: NoOfferMatchReason) =>
            val nextProcessed = processed - summary.getOrElse(reason, 0)
            (nextProcessed, seq :+ DeclinedOfferStep(reason.toString, summary.getOrElse(reason, 0), processed))
        }
        rejectSummaryLastOffers
      }

      def processedOffersSummary: ProcessedOffersSummary = {
        ProcessedOffersSummary(
          processedOffersCount = info.processedOffersCount,
          unusedOffersCount = info.unusedOffersCount,
          lastUnusedOfferAt = info.lastNoMatch.map(_.timestamp.toOffsetDateTime),
          lastUsedOfferAt = info.lastMatch.map(_.timestamp.toOffsetDateTime),
          rejectSummaryLastOffers = declinedOfferSteps(info.lastNoMatches.size, info.rejectSummaryLastOffers),
          rejectSummaryLaunchAttempt = declinedOfferSteps(info.processedOffersCount, info.rejectSummaryLaunchAttempt)
        )
      }

      val lastUnusedOffers = if (withLastUnused) Some(Raml.toRaml(info.lastNoMatches)) else None

      info.runSpec match {
        case app: AppDefinition =>
          QueueApp(
            info.instancesLeftToLaunch,
            info.role,
            delay,
            info.startedAt.toOffsetDateTime,
            processedOffersSummary,
            lastUnusedOffers,
            Raml.toRaml(app)
          )
        case pod: PodDefinition =>
          QueuePod(
            info.instancesLeftToLaunch,
            info.role,
            delay,
            info.startedAt.toOffsetDateTime,
            processedOffersSummary,
            lastUnusedOffers,
            Raml.toRaml(pod)
          )
      }
  }

  implicit val queueWrites: Writes[(Seq[QueuedInstanceInfoWithStatistics], Boolean, Clock), Queue] = Writes {
    case (infos, withLastUnused, clock) =>
      Queue(infos.map(info => Raml.toRaml((info, withLastUnused, clock))))
  }
}
