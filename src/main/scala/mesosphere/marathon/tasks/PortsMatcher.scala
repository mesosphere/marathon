package mesosphere.marathon.tasks

import mesosphere.marathon.state.{ AppDefinition, Container }
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.mesos.protos
import mesosphere.mesos.protos.{ RangesResource, Resource }
import mesosphere.util.Logging
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Offer

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.util.Random

/**
  * Utility class for checking if the ports resource in an offer matches the requirements of an app.
  */
class PortsMatcher(
  app: AppDefinition,
  offer: Offer,
  acceptedResourceRoles: Set[String] = Set("*"),
  random: Random = Random)
    extends Logging {

  import PortsMatcher._

  /**
    * The resulting port matches which should be consumed from the offer. If no matching port ranges could
    * be generated from the offer, return `None`.
    */
  lazy val portRanges: Option[Seq[RangesResource]] = portsWithRoles.map(PortWithRole.createPortsResources)

  /**
    * @return true if and only if the port requirements could be fulfilled by the given offer.
    */
  def matches: Boolean = portRanges.isDefined

  /**
    * @return the resulting assigned (host) ports.
    */
  def ports: Seq[Long] = for {
    resource <- portRanges.getOrElse(Nil)
    range <- resource.ranges
    port <- range.asScala()
  } yield port

  private[this] def portsWithRoles: Option[Seq[PortWithRole]] = {
    val portMappings: Option[Seq[Container.Docker.PortMapping]] =
      for {
        c <- app.container
        d <- c.docker
        pms <- d.portMappings if pms.nonEmpty
      } yield pms

    (app.ports, portMappings) match {
      case (Nil, None) => // optimization for empty special case
        Some(Seq.empty)

      case (appPortSpec, Some(mappings)) =>
        // We use the mappings from the containers if they are available and ignore any other port specification.
        // We cannot warn about this because we autofill the ports field.
        mappedPortRanges(mappings)

      case (appPorts, None) if app.requirePorts =>
        findPortsInOffer(appPorts, failLog = true)

      case (appPorts, None) if !app.ports.contains(Integer.valueOf(0)) =>
        // We try to use the user supplied ports as host ports if possible, fallback to dynamically assigned ports.
        findPortsInOffer(appPorts, failLog = false).orElse(randomPorts(appPorts.size))

      case (appPorts, None) =>
        randomPorts(appPorts.size)
    }
  }

  /**
   * Try to find supplied ports in offer. Returns `None` if not all ports were found.
   */
  private[this] def findPortsInOffer(requiredPorts: Seq[Integer], failLog: Boolean): Option[Seq[PortWithRole]] = {
    hasExpectedSizeOpt(expectedSize = requiredPorts.size) {
      requiredPorts.sorted.iterator.map { port =>
        offeredPortRanges.find(_.scalaRange.contains(port)).map { offeredRange =>
          PortWithRole(offeredRange.role, port)
        } orElse {
          if (failLog) log.info(s"Couldn't find host port $port in any offered range for app [${app.id}]")
          None
        }
      }.takeWhile(_.isDefined).flatten.to[Seq]
    }
  }

  /**
    * Choose random ports from offer.
    */
  private[this] def randomPorts(numberOfPorts: Int): Option[Seq[PortWithRole]] = {
    hasExpectedSizeOpt(expectedSize = numberOfPorts) {
      shuffledAvailablePorts.take(numberOfPorts).to[Seq]
    } orElse {
      log.info(s"Couldn't find $numberOfPorts ports in offer for app [${app.id}]")
      None
    }
  }

  /**
    * Try to find all non-zero host ports in offer and use random ports from the offer for dynamic host ports (=0).
    * Return `None` if not all host ports could be assigned this way.
    */
  private[this] def mappedPortRanges(mappings: Seq[PortMapping]): Option[Seq[PortWithRole]] = {
    hasExpectedSizeOpt(expectedSize = mappings.size) {
      // non-dynamic hostPorts from port mappings
      val hostPortsFromMappings: Set[Integer] = mappings.iterator.map(_.hostPort).filter(_ != 0).toSet

      // available ports without the ports that have been preset in the port mappings
      val availablePortsWithoutStaticHostPorts: Iterator[PortWithRole] =
        shuffledAvailablePorts.filter(portWithRole => !hostPortsFromMappings(portWithRole.port))

      mappings.iterator.map {
        case PortMapping(containerPort, hostPort, servicePort, protocol) if hostPort == 0 =>
          if (!availablePortsWithoutStaticHostPorts.hasNext) {
            log.info(s"Insufficient ports in offer for app [${app.id}]")
            None
          }
          else {
            Option(availablePortsWithoutStaticHostPorts.next())
          }
        case pm: PortMapping =>
          offeredPortRanges.find(_.scalaRange.contains(pm.hostPort)) match {
            case Some(PortRange(role, _)) =>
              Some(PortWithRole(role, pm.hostPort))
            case None =>
              log.info(s"Cannot find range with host port ${pm.hostPort} for app [${app.id}]")
              None
          }
      }.takeWhile(_.isDefined).flatten.to[Seq]
    }
  }

  private[this] def hasExpectedSizeOpt[T](expectedSize: Int)(ports: Seq[T]): Option[Seq[T]] = {
    if (ports.size == expectedSize) Some(ports) else None
  }

  private[this] lazy val offeredPortsResources: Seq[Protos.Resource] = offer.getResourcesList.asScala
    .filter(resource => acceptedResourceRoles(resource.getRole))
    .filter(_.getName == Resource.PORTS)
    .to[Seq]

  private[this] lazy val offeredPortRanges: Seq[PortRange] = offeredPortsResources
    .flatMap(resource => resource.getRanges.getRangeList.asScala.map(PortRange(resource.getRole, _)))

  private[this] def shuffledAvailablePorts: Iterator[PortWithRole] =
    PortWithRole.randomPortsFromRanges(random)(offeredPortRanges)
}

object PortsMatcher {

  case class PortWithRole(role: String, port: Int) {
    def toRange: protos.Range = {
      protos.Range(port.toLong, port.toLong)
    }
  }

  object PortWithRole {
    /**
      * Return RangesResources covering all given ports with the given roles.
      *
      * Creates as few RangesResources as possible while
      * preserving the order of the ports.
      */
    def createPortsResources(resources: Seq[PortWithRole]): Seq[RangesResource] = {
      val builder = Seq.newBuilder[RangesResource]
      @tailrec
      def process(resources: Seq[PortWithRole]): Unit = resources.headOption match {
        case None =>
        case Some(PortWithRole(role, _)) =>
          val portsForResource: Seq[PortWithRole] = resources.takeWhile(_.role == role)
          builder += RangesResource(name = Resource.PORTS, createRanges(portsForResource), role = role)
          process(resources.drop(portsForResource.size))
      }
      process(resources)

      builder.result()
    }

    /**
      * Create as few ranges as possible from the given ports while preserving the order of the ports.
      *
      * It does not check if the given ports have different roles.
      */
    private[this] def createRanges(ranges: Seq[PortWithRole]): Seq[protos.Range] = {
      val builder = Seq.newBuilder[protos.Range]

      @tailrec
      def process(lastRangeOpt: Option[protos.Range], next: Seq[PortWithRole]): Unit = {
        (lastRangeOpt, next.headOption) match {
          case (None, _) =>
          case (Some(lastRange), None) =>
            builder += lastRange
          case (Some(lastRange), Some(nextPort)) if lastRange.end == nextPort.port - 1 =>
            process(Some(lastRange.copy(end = nextPort.port.toLong)), next.tail)
          case (Some(lastRange), Some(nextPort)) =>
            builder += lastRange
            process(Some(nextPort.toRange), next.tail)
        }
      }
      process(ranges.headOption.map(_.toRange), ranges.tail)

      builder.result()
    }

    /**
      * We want to avoid reusing the same dynamic ports. Thus we shuffle the ports to make it less likely.
      *
      * The implementation idea:
      *
      * * Randomize the order of the offered ranges.
      * * Now treat the ports contained in the ranges as one long sequence of ports.
      * * We randomly choose an index where we want to start assigning dynamic ports in that sequence. When
      *   we hit the last offered port with wrap around and start offering the ports at the beginning
      *   of the sequence up to (excluding) the port index we started at.
      */
    def randomPortsFromRanges(random: Random = Random)(offeredPortRanges: Seq[PortRange]): Iterator[PortWithRole] = {
      val numberOfOfferedPorts = offeredPortRanges.map(_.scalaRange.size).sum

      if (numberOfOfferedPorts == 0) {
        return Iterator.empty
      }

      def findStartPort(shuffled: Vector[PortRange], startPortIdx: Int): (Int, Int) = {
        var startPortIdxOfCurrentRange = 0
        val rangeIdx = shuffled.indexWhere {
          case range: PortRange if startPortIdxOfCurrentRange + range.scalaRange.size > startPortIdx =>
            true
          case range: PortRange =>
            startPortIdxOfCurrentRange += range.scalaRange.size
            false
        }

        (rangeIdx, startPortIdx - startPortIdxOfCurrentRange)
      }

      val shuffled = Random.shuffle(offeredPortRanges).toVector
      val startPortIdx = Random.nextInt(numberOfOfferedPorts)

      val (rangeIdx, portInRangeIdx) = findStartPort(shuffled, startPortIdx)
      val startRangeOrig = shuffled(rangeIdx)

      val startRange = startRangeOrig.withoutNPorts(portInRangeIdx)
      val afterStartRange = shuffled.slice(rangeIdx + 1, shuffled.length).flatMap(_.portsWithRolesIterator)
      val beforeStartRange = shuffled.slice(0, rangeIdx).flatMap(_.portsWithRolesIterator)
      val endRange = startRangeOrig.firstNPorts(portInRangeIdx)

      startRange ++ afterStartRange ++ beforeStartRange ++ endRange
    }
  }

  case class PortRange(role: String, range: Protos.Value.Range) {
    lazy val scalaRange: Range = range.getBegin.toInt to range.getEnd.toInt

    def portsWithRolesIterator: Iterator[PortWithRole] = scalaRange.iterator.map(PortWithRole(role, _))
    def firstNPorts(n: Int): Iterator[PortWithRole] = scalaRange.take(n).iterator.map(PortWithRole(role, _))
    def withoutNPorts(n: Int): Iterator[PortWithRole] = scalaRange.drop(n).iterator.map(PortWithRole(role, _))
  }
}