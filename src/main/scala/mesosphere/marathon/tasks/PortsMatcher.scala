package mesosphere.marathon.tasks

import org.apache.mesos.Protos

import scala.util.{ Try, Random }
import org.apache.mesos.Protos.{ Value, Offer }
import mesosphere.mesos.protos
import mesosphere.mesos.protos.{ RangesResource, Resource }
import mesosphere.marathon.PortResourceException
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.Container
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.util.Logging
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

object PortsMatcher {
  case class PortRange(role: String, range: Protos.Value.Range) {
    lazy val scalaRange: Range = range.getBegin.toInt to range.getEnd.toInt
  }
}

/**
 * Utility class for checking if the ports resource in an offer matches the requirements of an app.
 */
class PortsMatcher(app: AppDefinition, offer: Offer, acceptedResourceRoles: Set[String] = Set("*")) extends Logging {
  import PortsMatcher._

  private[this] lazy val offeredPortsResources: Seq[Protos.Resource] = offer.getResourcesList.asScala
    .filter(resource => acceptedResourceRoles(resource.getRole))
    .filter(_.getName == Resource.PORTS)
    .to[Seq]

  private[this] lazy val offeredPortRanges: Seq[PortRange] = offeredPortsResources
    .flatMap(resource => resource.getRanges.getRangeList.asScala.map(PortRange(resource.getRole, _)))

  /**
   * The resulting port matches which should be consumed from the offer. If no matching port ranges could
   * be generated from the offer, return `None`.
   */
  lazy val portRanges: Option[Seq[RangesResource]] = {
    val portMappings: Option[Seq[Container.Docker.PortMapping]] =
      for {
        c <- app.container
        d <- c.docker
        pms <- d.portMappings if pms.nonEmpty
      } yield pms

    (app.ports, portMappings) match {
      case (Nil, None) =>
        Some(Seq.empty)

      case (appPorts, None) if app.requirePorts =>
        appPortRanges.map(Seq(_))

      case (appPorts, None) if app.ports.forall(_ != 0) =>
        appPortRanges.orElse(randomPortRanges).map(Seq(_))

      case (appPorts, None) =>
        randomPortRanges.map(Seq(_))

      case (_, Some(mappings)) =>
        val assigned: Try[Seq[RangesResource]] = mappedPortRanges(mappings)
        assigned.recover {
          case PortResourceException(msg) => log.info(msg)
        }: Any
        assigned.toOption
    }
  }

  /**
   * @return true if and only if the port requirements could be fulfilled by the given offer.
   */
  def matches: Boolean = portRanges.isDefined

  /**
   * @return the resulting assigned ports.
   */
  def ports: Seq[Long] = portRanges.map(_.flatMap(_.ranges.flatMap(_.asScala()))).getOrElse(Nil)

  /**
   * Try to satisfy all app.ports exactly (not dynamically) if that's possible to do with one of the offered
   * ranges.
   */
  // TODO use multiple ranges if one is not enough
  private def appPortRanges: Option[RangesResource] = {
    val sortedPorts = app.ports.sorted
    val firstPort = sortedPorts.head
    val lastPort = sortedPorts.last

    lazy val matchingRange: Option[PortRange] = offeredPortRanges.find {
      case PortRange(_, range) => range.getBegin <= firstPort && range.getEnd >= lastPort
      case _                   => false
    }

    // Monotonically increasing ports
    if (firstPort + sortedPorts.size - 1 == lastPort && matchingRange.isDefined) {
      Some(RangesResource(
        Resource.PORTS, Seq(protos.Range(firstPort.longValue, lastPort.longValue)),
        matchingRange.get.role))
    }
    else {
      val suitablePortRangeOpt = offeredPortRanges
        .iterator
        .find {
        case PortRange(_, range) => app.ports.forall(p => range.getBegin <= p && range.getEnd >= p)
      }
      suitablePortRangeOpt.map {
        case PortRange(role, range) =>
          val portRanges = app.ports.map(p => protos.Range(p.longValue, p.longValue))
          RangesResource(Resource.PORTS, portRanges, role)
      }
    }
  }

  /**
   * @return dynamically assign ports if that's possible to do with one of the offered
   * ranges.
   */
  // TODO use multiple ranges if one is not enough
  private def randomPortRanges: Option[RangesResource] = {
    for (PortRange(role, range) <- offeredPortRanges) {
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
  private def mappedPortRanges(mappings: Seq[PortMapping]): Try[Seq[RangesResource]] =
    Try {
      case class PortWithRole(role: String, port: Int)

      // non-dynamic hostPorts from app definitions
      val hostPortsFromMappings: Set[Integer] = mappings.iterator.map(_.hostPort).filter(_ != 0).toSet

      // available ports without the ports that have been preset in the port mappings
      val availablePorts: Iterator[PortWithRole] =
        offeredPortRanges.foldLeft(Iterator.apply[PortWithRole]()) {
          case (acc, PortRange(role, r)) =>
            acc ++ Iterator.range(r.getBegin.toInt, r.getEnd.toInt + 1).map(PortWithRole(role, _))
        }.filter(portWithRole => !hostPortsFromMappings(portWithRole.port))

      case class PortMappingWithRole(role: String, mapping: PortMapping)

      val mappingsWithAssignedRandoms: Seq[PortMappingWithRole] = mappings.map {
        case PortMapping(containerPort, hostPort, servicePort, protocol) if hostPort == 0 =>
          if (!availablePorts.hasNext) throw PortResourceException(
            s"Insufficient ports in offer for app [${app.id}]"
          )
          val portWithRole = availablePorts.next()
          PortMappingWithRole(
            portWithRole.role,
            PortMapping(containerPort, portWithRole.port, servicePort, protocol)
          )
        case pm: PortMapping =>
          val role = offeredPortRanges.find(_.scalaRange.contains(pm.hostPort)) match {
            case Some(PortRange(role, _)) => role
            case None =>
              throw PortResourceException(
                s"Cannot find range with port ${pm.hostPort}"
              )
          }
          PortMappingWithRole(role, pm)
      }

      val byRole: Map[String, Seq[PortMapping]] =
        mappingsWithAssignedRandoms.groupBy(_.role).mapValues(_.map(_.mapping))

      byRole.map {
        case (role, portMappings) =>
          val portRanges = portMappings.map {
            case PortMapping(containerPort, hostPort, servicePort, protocol) =>
              protos.Range(hostPort.longValue, hostPort.longValue)
          }

          RangesResource(Resource.PORTS, portRanges, role)
      }.to[Seq]
    }

}