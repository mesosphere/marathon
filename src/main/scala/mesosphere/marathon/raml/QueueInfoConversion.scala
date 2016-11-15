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
    def create(scalar: Option[Double], ranges: Option[Seq[NumberRange]], set: Option[Seq[String]]) =
      OfferResource(resource.getName, resource.getRole, scalar, ranges, set)
    resource.getType match {
      case Mesos.Value.Type.SCALAR => create(Some(resource.getScalar.getValue), None, None)
      case Mesos.Value.Type.RANGES => create(None, Some(Raml.toRaml(resource.getRanges.getRangeList.toSeq)), None)
      case Mesos.Value.Type.SET => create(None, None, Some(resource.getSet.getItemList.toSeq))
      case _ => create(None, None, None)
    }
  }

  implicit val offerAttributeWrites: Writes[Mesos.Attribute, AgentAttribute] = Writes { attribute =>
    def create(scalar: Option[Double], ranges: Option[Seq[NumberRange]], set: Option[Seq[String]], text: Option[String]) =
      AgentAttribute(attribute.getName, text, scalar, ranges, set)
    attribute.getType match {
      case Mesos.Value.Type.SCALAR => create(Some(attribute.getScalar.getValue), None, None, None)
      case Mesos.Value.Type.RANGES => create(None, Some(Raml.toRaml(attribute.getRanges.getRangeList.toSeq)), None, None)
      case Mesos.Value.Type.SET => create(None, None, Some(attribute.getSet.getItemList.toSeq), None)
      case Mesos.Value.Type.TEXT => create(None, None, None, Option(attribute.getText.getValue))
    }
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
