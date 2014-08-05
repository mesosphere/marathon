package mesosphere.marathon.tasks

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.mesos.protos
import scala.util.Random
import org.apache.mesos.Protos.Offer
import mesosphere.mesos.protos.{ RangesResource, Resource }
import scala.collection.JavaConverters._

/**
  * Utility class for checking if the ports resource in an offer matches the requirements of an app.
  */
class PortsMatcher(app: AppDefinition, offer: Offer) {

  val portsResource = offer.getResourcesList.asScala
    .find(_.getName == Resource.PORTS)
  val offeredPortRanges = portsResource
    .map(_.getRanges.getRangeList.asScala)
    .getOrElse(Nil)
  val role = portsResource.map(_.getRole).getOrElse("*")

  def portRanges: Option[RangesResource] = {
    if (app.ports.isEmpty) {
      Some(RangesResource(Resource.PORTS, Nil))
    }
    else if (app.requirePorts) {
      appPortRanges
    }
    else {
      appPortRanges.orElse(randomPortRanges)
    }
  }

  def matches: Boolean = {
    portRanges.isDefined
  }

  def ports: Seq[Long] = {
    portRanges.map(_.ranges.flatMap(_.asScala())).getOrElse(Nil)
  }

  private def appPortRanges: Option[RangesResource] = {
    val sortedPorts = app.ports.sorted
    val firstPort = sortedPorts.head
    val lastPort = sortedPorts.last

    // Monotonically increasing ports
    if (firstPort + sortedPorts.size - 1 == lastPort &&
      offeredPortRanges.exists(r => r.getBegin <= firstPort && r.getEnd >= lastPort)) {
      Some(RangesResource(Resource.PORTS, Seq(protos.Range(firstPort.longValue, lastPort.longValue)), role))
    }
    else if (app.ports.forall(p => offeredPortRanges.exists(r => r.getBegin <= p && r.getEnd >= p))) {
      val portRanges = app.ports.map(p => protos.Range(p.longValue, p.longValue))
      Some(RangesResource(Resource.PORTS, portRanges, role))
    }
    else {
      None
    }
  }

  private def randomPortRanges: Option[RangesResource] = {
    for (range <- offeredPortRanges) {
      // TODO use multiple ranges if one is not enough
      if (range.getEnd - range.getBegin + 1 >= app.ports.length) {
        val maxOffset = (range.getEnd - range.getBegin - app.ports.length + 2).toInt
        val firstPort = range.getBegin.toInt + Random.nextInt(maxOffset)
        val rangeProto = protos.Range(firstPort, firstPort + app.ports.length - 1)
        return Some(
          RangesResource(Resource.PORTS, Seq(rangeProto), role)
        )
      }
    }
    None
  }
}
