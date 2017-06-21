package mesosphere.marathon
package raml

import org.apache.mesos.{ Protos => Mesos }
import mesosphere.marathon.stream.Implicits._

trait OfferConversion {

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
      case Mesos.Value.Type.RANGES => create(None, Some(resource.getRanges.getRangeList.toRaml), None)
      case Mesos.Value.Type.SET => create(None, None, Some(resource.getSet.getItemList.toSeq))
      case _ => create(None, None, None)
    }
  }

  implicit val offerAttributeWrites: Writes[Mesos.Attribute, AgentAttribute] = Writes { attribute =>
    def create(scalar: Option[Double], ranges: Option[Seq[NumberRange]], set: Option[Seq[String]], text: Option[String]) =
      AgentAttribute(attribute.getName, text, scalar, ranges, set)
    attribute.getType match {
      case Mesos.Value.Type.SCALAR => create(Some(attribute.getScalar.getValue), None, None, None)
      case Mesos.Value.Type.RANGES => create(None, Some(attribute.getRanges.getRangeList.toRaml), None, None)
      case Mesos.Value.Type.SET => create(None, None, Some(attribute.getSet.getItemList.toSeq), None)
      case Mesos.Value.Type.TEXT => create(None, None, None, Option(attribute.getText.getValue))
    }
  }

  implicit val offerWrites: Writes[Mesos.Offer, Offer] = Writes { offer =>
    Offer(
      offer.getId.getValue,
      offer.getHostname,
      offer.getSlaveId.getValue,
      offer.getResourcesList.toRaml,
      offer.getAttributesList.toRaml
    )
  }
}
