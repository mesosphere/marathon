package mesosphere.marathon.tasks

import scala.util.{ Try, Random }
import org.apache.mesos.Protos.Offer
import mesosphere.mesos.protos
import mesosphere.mesos.protos.{ RangesResource, Resource }
import mesosphere.marathon.PortResourceException
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.Container
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.util.Logging
import scala.collection.JavaConverters._

/**
  * Utility class for checking if the ports resource in an offer matches the requirements of an app.
  */
class PortsMatcher(app: AppDefinition, offer: Offer) extends Logging {

  val portsResource = offer.getResourcesList.asScala
    .find(_.getName == Resource.PORTS)

  val offeredPortRanges = portsResource
    .map(_.getRanges.getRangeList.asScala)
    .getOrElse(Nil)

  val role = portsResource.map(_.getRole).getOrElse("*")

  def portRanges: Option[RangesResource] = {

    val portMappings: Option[Seq[Container.Docker.PortMapping]] =
      for {
        c <- app.container
        d <- c.docker
        pms <- d.portMappings if pms.nonEmpty
      } yield pms

    (app.ports, portMappings) match {
      case (Nil, None) =>
        Some(RangesResource(Resource.PORTS, Nil))

      case (appPorts, None) if app.requirePorts =>
        appPortRanges

      case (appPorts, None) =>
        appPortRanges.orElse(randomPortRanges)

      case (_, Some(mappings)) =>
        val assigned: Try[RangesResource] = mappedPortRanges(mappings)
        assigned.recover {
          case PortResourceException(msg) => log.info(msg)
        }: Any
        assigned.toOption

      case _ =>
        Some(RangesResource(Resource.PORTS, Nil))
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
        val firstPort = range.getBegin + Random.nextInt(maxOffset)
        val rangeProto = protos.Range(firstPort, firstPort + app.ports.length - 1)
        return Some(
          RangesResource(Resource.PORTS, Seq(rangeProto), role)
        )
      }
    }
    None
  }

  /**
    * Returns Some(rangesResources) if the zero-valued docker host-ports
    * can be assigned to ANY port from the resource offer, AND all other
    * (non-zero-valued) docker host-ports are available in the resource offer.
    */
  private def mappedPortRanges(mappings: Seq[PortMapping]): Try[RangesResource] =
    Try {
      val availablePorts: Iterator[Int] =
        offeredPortRanges.foldLeft(Iterator.apply[Int]()) {
          (acc, r) => acc ++ Iterator.range(r.getBegin.toInt, r.getEnd.toInt + 1)
        }

      val scalaPortRanges: Seq[Range] =
        offeredPortRanges.map { r => r.getBegin.toInt to r.getEnd.toInt }

      def portInOffer(port: Int): Boolean =
        scalaPortRanges.exists(_ contains port)

      val mappingsWithAssignedRandoms = mappings.map {
        case PortMapping(containerPort, hostPort, servicePort, protocol) if hostPort == 0 =>
          if (!availablePorts.hasNext) throw PortResourceException(
            s"Insufficient ports in offer for app [${app.id}]"
          )
          PortMapping(containerPort, availablePorts.next, servicePort, protocol)
        case pm: PortMapping => pm
      }

      // ensure that each assigned port is present in the resource offer
      val allPortsInOffer = mappingsWithAssignedRandoms.forall { mapping =>
        portInOffer(mapping.hostPort)
      }

      if (!allPortsInOffer)
        throw PortResourceException(
          "All assigned host ports must be present in the resource offer.\n" +
            "Assigned: [" + mappingsWithAssignedRandoms.mkString(", ") + "]"
        )

      val portRanges = mappingsWithAssignedRandoms.map {
        case PortMapping(containerPort, hostPort, servicePort, protocol) =>
          protos.Range(hostPort.longValue, hostPort.longValue)
      }

      RangesResource(Resource.PORTS, portRanges, role)
    }

}
