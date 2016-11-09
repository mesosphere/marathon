package mesosphere.marathon
package raml

import java.time.OffsetDateTime

import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.LaunchQueue.QueuedInstanceInfoWithStatistics
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.AppDefinition
import mesosphere.mesos.NoOfferMatchReason
import org.apache.mesos.{ Protos => Mesos }
import mesosphere.marathon.stream._

trait QueueInfoConversion extends DefaultConversions {

  implicit val rejectReasonWrites: Writes[NoOfferMatchReason, String] = Writes { _.toString }

  implicit val scalarWrites: Writes[Mesos.Value.Scalar, Option[Double]] = Writes { scalar =>
    if (scalar.hasValue) Some(scalar.getValue) else None
  }

  implicit val rangeWrites: Writes[Mesos.Value.Range, NumberRange] = Writes { range =>
    NumberRange(range.getBegin, range.getEnd)
  }

  implicit val offerResourceWrites: Writes[Mesos.Resource, OfferResource] = Writes { resource =>
    OfferResource(
      resource.getName,
      resource.getRole,
      Raml.toRaml(resource.getScalar),
      Raml.toRaml(resource.getRanges.getRangeList.toSeq),
      resource.getSet.getItemList.toSeq
    )
  }

  implicit val offerAttributeWrites: Writes[Mesos.Attribute, AgentAttribute] = Writes { attribute =>
    AgentAttribute(
      attribute.getName,
      Raml.toRaml(attribute.getScalar),
      Raml.toRaml(attribute.getRanges.getRangeList.toSeq),
      attribute.getSet.getItemList.toSeq
    )
  }

  implicit val offerWrites: Writes[Mesos.Offer, Offer] = Writes { offer =>
    Offer(
      offer.getId.getValue,
      offer.getHostname,
      offer.getSlaveId.getValue,
      Raml.toRaml(offer.getResourcesList.toSeq),
      Raml.toRaml(offer.getAttributesList.toSeq)
    )
  }

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

      def processedOffersSummary: ProcessedOffersSummary = ProcessedOffersSummary(
        info.processedOfferCount,
        info.unusedOfferCount,
        info.lastNoMatch.map(_.timestamp.toOffsetDateTime),
        info.lastMatch.map(_.timestamp.toOffsetDateTime),
        Raml.toRaml(info.rejectSummary)
      )

      def queueItem[A](create: (Int, Option[QueueDelay], OffsetDateTime, ProcessedOffersSummary, Seq[UnusedOffer]) => A): A = {
        create(
          info.instancesLeftToLaunch,
          delay,
          info.startedAt.toOffsetDateTime,
          processedOffersSummary,
          if (withLastUnused) Raml.toRaml(info.lastNoMatches) else Seq.empty
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
