package mesosphere.marathon.tasks

import mesosphere.marathon.state.{ ResourceRole, AppDefinition, Container }
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.tasks.PortsMatcher.PortWithRole
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import mesosphere.mesos.protos
import mesosphere.mesos.protos.{ RangesResource, Resource }
import mesosphere.util.Logging
import org.apache.mesos.{ Protos => MesosProtos }

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.util.Random

case class PortsMatch(hostPortsWithRole: Seq[PortWithRole]) {
  /**
    * The resulting port resources which should be consumed from the offer. If no matching port ranges could
    * be generated from the offer, return `None`.
    */
  lazy val resources: Seq[MesosProtos.Resource] = PortWithRole.createPortsResources(hostPortsWithRole)

  def hostPorts: Seq[Int] = hostPortsWithRole.map(_.port)
}

/**
  * Utility class for checking if the ports resource in an offer matches the requirements of an app.
  */
class PortsMatcher(
  app: AppDefinition,
  offer: MesosProtos.Offer,
  resourceSelector: ResourceSelector = ResourceSelector(Set(ResourceRole.Unreserved), reserved = false),
  random: Random = Random)
    extends Logging {

  import PortsMatcher._

  lazy val portsMatch: Option[PortsMatch] = portsWithRoles.map(PortsMatch(_))

  private[this] def portsWithRoles: Option[Seq[PortWithRole]] = {
    val portMappings: Option[Seq[Container.Docker.PortMapping]] =
      for {
        c <- app.container
        d <- c.docker
        pms <- d.portMappings if pms.nonEmpty
      } yield pms

    (app.portNumbers, portMappings) match {
      case (Nil, None) => // optimization for empty special case
        Some(Seq.empty)

      case (appPortSpec, Some(mappings)) =>
        // We use the mappings from the containers if they are available and ignore any other port specification.
        // We cannot warn about this because we autofill the ports field.
        mappedPortRanges(mappings)

      case (appPorts, None) if app.requirePorts =>
        findPortsInOffer(appPorts, failLog = true)

      case (appPorts, None) =>
        randomPorts(appPorts.size)
    }
  }

  /**
    * Try to find supplied ports in offer. Returns `None` if not all ports were found.
    */
  private[this] def findPortsInOffer(requiredPorts: Seq[Int], failLog: Boolean): Option[Seq[PortWithRole]] = {
    takeEnoughPortsOrNone(expectedSize = requiredPorts.size) {
      requiredPorts.iterator.map { (port: Int) =>
        offeredPortRanges.find(_.contains(port)).map { offeredRange =>
          PortWithRole(offeredRange.role, port, offeredRange.reservation)
        } orElse {
          if (failLog)
            log.info(
              s"Offer [${offer.getId.getValue}]. $resourceSelector. " +
                s"Couldn't find host port $port (of ${requiredPorts.mkString(", ")}) " +
                s"in any offered range for app [${app.id}]")
          None
        }
      }
    }
  }

  /**
    * Choose random ports from offer.
    */
  private[this] def randomPorts(numberOfPorts: Int): Option[Seq[PortWithRole]] = {
    takeEnoughPortsOrNone(expectedSize = numberOfPorts) {
      shuffledAvailablePorts.map(Some(_))
    } orElse {
      log.info(s"Offer [${offer.getId.getValue}]. $resourceSelector. " +
        s"Couldn't find $numberOfPorts ports in offer for app [${app.id}]")
      None
    }
  }

  /**
    * Try to find all non-zero host ports in offer and use random ports from the offer for dynamic host ports (=0).
    * Return `None` if not all host ports could be assigned this way.
    */
  private[this] def mappedPortRanges(mappings: Seq[PortMapping]): Option[Seq[PortWithRole]] = {
    takeEnoughPortsOrNone(expectedSize = mappings.size) {
      // non-dynamic hostPorts from port mappings
      val hostPortsFromMappings: Set[Int] = mappings.iterator.map(_.hostPort).filter(_ != 0).toSet

      // available ports without the ports that have been preset in the port mappings
      val availablePortsWithoutStaticHostPorts: Iterator[PortWithRole] =
        shuffledAvailablePorts.filter(portWithRole => !hostPortsFromMappings(portWithRole.port))

      mappings.iterator.map {
        case PortMapping(containerPort, hostPort, servicePort, protocol, name, labels) if hostPort == 0 =>
          if (!availablePortsWithoutStaticHostPorts.hasNext) {
            log.info(s"Offer [${offer.getId.getValue}]. $resourceSelector. " +
              s"Insufficient ports in offer for app [${app.id}]")
            None
          }
          else {
            Option(availablePortsWithoutStaticHostPorts.next())
          }
        case pm: PortMapping =>
          offeredPortRanges.find(_.contains(pm.hostPort)) match {
            case Some(PortRange(role, _, _, reservation)) =>
              Some(PortWithRole(role, pm.hostPort, reservation))
            case None =>
              log.info(s"Offer [${offer.getId.getValue}]. $resourceSelector. " +
                s"Cannot find range with host port ${pm.hostPort} for app [${app.id}]")
              None
          }
      }
    }
  }

  /**
    * Takes `expectedSize` ports from the given iterator if possible. Stops when encountering the first `None` port.
    */
  private[this] def takeEnoughPortsOrNone[T](expectedSize: Int)(ports: Iterator[Option[T]]): Option[Seq[T]] = {
    val allocatedPorts = ports.takeWhile(_.isDefined).take(expectedSize).flatten.toVector
    if (allocatedPorts.size == expectedSize) Some(allocatedPorts) else None
  }

  private[this] lazy val offeredPortRanges: Seq[PortRange] = {
    val portRangeIter = for {
      resource <- offer.getResourcesList.asScala.iterator
      if resourceSelector(resource) && resource.getName == Resource.PORTS
      rangeInResource <- resource.getRanges.getRangeList.asScala
      reservation = if (resource.hasReservation) Option(resource.getReservation) else None
    } yield PortRange(resource.getRole, rangeInResource.getBegin.toInt, rangeInResource.getEnd.toInt, reservation)
    portRangeIter.to[Seq]
  }

  private[this] def shuffledAvailablePorts: Iterator[PortWithRole] =
    PortWithRole.lazyRandomPortsFromRanges(random)(offeredPortRanges)
}

object PortsMatcher {

  case class PortWithRole(role: String, port: Int, reservation: Option[MesosProtos.Resource.ReservationInfo] = None) {
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
    def createPortsResources(resources: Seq[PortWithRole]): Seq[MesosProtos.Resource] = {
      /*
       * Create as few ranges as possible from the given ports while preserving the order of the ports.
       *
       * It does not check if the given ports have different roles.
       */
      def createRanges(ranges: Seq[PortWithRole]): Seq[protos.Range] = {
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

      val builder = Seq.newBuilder[MesosProtos.Resource]
      @tailrec
      def process(resources: Seq[PortWithRole]): Unit = resources.headOption match {
        case None =>
        case Some(PortWithRole(role, _, reservation)) =>
          val portsForResource: Seq[PortWithRole] = resources.takeWhile { port =>
            port.role == role && port.reservation == reservation
          }
          import mesosphere.mesos.protos.Implicits._
          val resourceBuilder = RangesResource(name = Resource.PORTS, createRanges(portsForResource), role = role)
            .toBuilder
          reservation.foreach(resourceBuilder.setReservation(_))
          builder += resourceBuilder.build()
          process(resources.drop(portsForResource.size))
      }
      process(resources)

      builder.result()
    }

    /**
      * We want to make it less likely that we are reusing the same dynamic port for tasks of different apps.
      * This way we allow load balancers to reconfigure before reusing the same ports.
      *
      * Therefore we want to choose dynamic ports randomly from all the offered port ranges.
      * We want to use consecutive ports to avoid excessive range fragmentation.
      *
      * The implementation idea:
      *
      * * Randomize the order of the offered ranges.
      * * Now treat the ports contained in the ranges as one long sequence of ports.
      * * We randomly choose an index where we want to start assigning dynamic ports in that sequence. When
      *   we hit the last offered port with wrap around and start offering the ports at the beginning
      *   of the sequence up to (excluding) the port index we started at.
      */
    def lazyRandomPortsFromRanges(rand: Random = Random)(offeredPortRanges: Seq[PortRange]): Iterator[PortWithRole] = {
      val numberOfOfferedPorts = offeredPortRanges.map(_.size).sum

      if (numberOfOfferedPorts == 0) {
        //scalastyle:off return
        return Iterator.empty
        //scalastyle:on
      }

      def findStartPort(shuffled: Vector[PortRange], startPortIdx: Int): (Int, Int) = {
        var startPortIdxOfCurrentRange = 0
        val rangeIdx = shuffled.indexWhere {
          case range: PortRange if startPortIdxOfCurrentRange + range.size > startPortIdx =>
            true
          case range: PortRange =>
            startPortIdxOfCurrentRange += range.size
            false
        }

        (rangeIdx, startPortIdx - startPortIdxOfCurrentRange)
      }

      val shuffled = rand.shuffle(offeredPortRanges).toVector
      val startPortIdx = rand.nextInt(numberOfOfferedPorts)
      val (rangeIdx, portInRangeIdx) = findStartPort(shuffled, startPortIdx)
      val startRangeOrig = shuffled(rangeIdx)

      val startRange = startRangeOrig.withoutNPorts(portInRangeIdx)

      // These are created on demand if necessary
      def afterStartRange: Iterator[PortWithRole] =
        shuffled.slice(rangeIdx + 1, shuffled.length).iterator.flatMap(_.portsWithRolesIterator)
      def beforeStartRange: Iterator[PortWithRole] =
        shuffled.slice(0, rangeIdx).iterator.flatMap(_.portsWithRolesIterator)
      def endRange: Iterator[PortWithRole] = startRangeOrig.firstNPorts(portInRangeIdx)

      startRange ++ afterStartRange ++ beforeStartRange ++ endRange
    }
  }

  case class PortRange(
      role: String, minPort: Int, maxPort: Int,
      reservation: Option[MesosProtos.Resource.ReservationInfo] = None) {
    private[this] def range: Range.Inclusive = Range.inclusive(minPort, maxPort)

    def size: Int = range.size
    /*
     * Attention! range exports _two_ contains methods, a generic inefficient one and an efficient one
     * that only gets used with Int (and not java.lang.Integer and similar)
     */
    def contains(port: Int): Boolean = range.contains(port)

    def portsWithRolesIterator: Iterator[PortWithRole] = range.iterator.map(PortWithRole(role, _, reservation))
    def firstNPorts(n: Int): Iterator[PortWithRole] = range.take(n).iterator.map(PortWithRole(role, _, reservation))
    def withoutNPorts(n: Int): Iterator[PortWithRole] = range.drop(n).iterator.map(PortWithRole(role, _, reservation))
  }
}
