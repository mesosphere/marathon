//package mesosphere.marathon.tasks
//
//import mesosphere.marathon.state.{ AppDefinition, Container }
//import mesosphere.marathon.state.Container.Docker.PortMapping
//import mesosphere.mesos.protos
//import mesosphere.mesos.protos.{ RangesResource, Resource }
//import mesosphere.util.Logging
//import org.apache.mesos.Protos.Offer
//
//import scala.annotation.tailrec
//import scala.collection.JavaConverters._
//import scala.collection.immutable.Seq
//import scala.util.Random
//
///**
//  * Utility class for checking if the ports resource in an offer matches the requirements of an app.
//  */
//class PortsMatcher(
//  app: AppDefinition,
//  offer: Offer,
//  acceptedResourceRoles: Set[String] = Set("*"),
//  random: Random = Random)
//    extends Logging {
//
//  import PortsMatcher._
//
//  /**
//    * The resulting port matches which should be consumed from the offer. If no matching port ranges could
//    * be generated from the offer, return `None`.
//    */
//  lazy val portRanges: Option[Seq[RangesResource]] = portsWithRoles.map(RangeWithRole.createPortsResources)
//
//  /**
//    * @return true if and only if the port requirements could be fulfilled by the given offer.
//    */
//  def matches: Boolean = portRanges.isDefined
//
//  /**
//    * @return the resulting assigned (host) ports.
//    */
//  def ports: Seq[Long] = for {
//    resource <- portRanges.getOrElse(Nil)
//    range <- resource.ranges
//    port <- range.asScala()
//  } yield port
//
//  private[this] def portsWithRoles: Option[Seq[RangeWithRole]] = {
//    val portMappings: Option[Seq[Container.Docker.PortMapping]] =
//      for {
//        c <- app.container
//        d <- c.docker
//        pms <- d.portMappings if pms.nonEmpty
//      } yield pms
//
//    (app.ports, portMappings) match {
//      case (Nil, None) => // optimization for empty special case
//        Some(Seq.empty)
//
//      case (appPortSpec, Some(mappings)) =>
//        // We use the mappings from the containers if they are available and ignore any other port specification.
//        // We cannot warn about this because we autofill the ports field.
//        mappedPortRanges(mappings)
//
//      case (appPorts, None) if app.requirePorts =>
//        findRangesInOffer(appPorts, failLog = true)
//
//      case (appPorts, None) if !app.ports.contains(Integer.valueOf(0)) =>
//        // We try to use the user supplied ports as host ports if possible, fallback to dynamically assigned ports.
//        findRangesInOffer(appPorts, failLog = false).orElse(randomPorts(appPorts.size))
//
//      case (appPorts, None) =>
//        randomPorts(appPorts.size)
//    }
//  }
//
//  /**
//    * Try to find supplied ranges in offer. Returns `None` if not all ranges were found.
//    */
//  private[this] def findRangesInOffer(requiredRanges: Seq[Integer], failLog: Boolean): Option[Seq[RangeWithRole]] = {
//    takeEnoughRangesOrNone(expectedSize = requiredRanges.size) {
//      requiredRanges.sorted.iterator.map { r =>
//        offeredRanges.find(_.range.contains(r)).map { offeredRange =>
//          RangeWithRole(offeredRange.role, r)
//        } orElse {
//          if (failLog) log.info(s"Couldn't find host element $r in any offered range for app [${app.id}]")
//          None
//        }
//      }
//    }
//  }
//
//  /**
//    * Choose random ports from offer.
//    */
//  private[this] def randomPorts(numberOfPorts: Int): Option[Seq[RangeWithRole]] = {
//    takeEnoughRangesOrNone(expectedSize = numberOfPorts) {
//      shuffledAvailablePorts.map(Some(_))
//    } orElse {
//      log.info(s"Couldn't find $numberOfPorts ports in offer for app [${app.id}]")
//      None
//    }
//  }
//
//  /**
//    * Try to find all non-zero host ports in offer and use random ports from the offer for dynamic host ports (=0).
//    * Return `None` if not all host ports could be assigned this way.
//    */
//  private[this] def mappedPortRanges(mappings: Seq[PortMapping]): Option[Seq[RangeWithRole]] = {
//    takeEnoughRangesOrNone(expectedSize = mappings.size) {
//      // non-dynamic hostPorts from port mappings
//      val hostPortsFromMappings: Set[Integer] = mappings.iterator.map(_.hostPort).filter(_ != 0).toSet
//
//      // available ports without the ports that have been preset in the port mappings
//      val availablePortsWithoutStaticHostPorts: Iterator[RangeWithRole] =
//        shuffledAvailablePorts.filter(portWithRole => !hostPortsFromMappings(portWithRole.port))
//
//      mappings.iterator.map {
//        case PortMapping(containerPort, hostPort, servicePort, protocol) if hostPort == 0 =>
//          if (!availablePortsWithoutStaticHostPorts.hasNext) {
//            log.info(s"Insufficient ports in offer for app [${app.id}]")
//            None
//          }
//          else {
//            Option(availablePortsWithoutStaticHostPorts.next())
//          }
//        case pm: PortMapping =>
//          offeredRanges.find(_.range.contains(pm.hostPort)) match {
//            case Some(Range(role, _)) =>
//              Some(RangeWithRole(role, pm.hostPort))
//            case None =>
//              log.info(s"Cannot find range with host port ${pm.hostPort} for app [${app.id}]")
//              None
//          }
//      }
//    }
//  }
//
//  /**
//    * Takes `expectedSize` ports from the given iterator if possible. Stops when encountering the first `None` port.
//    */
//  private[this] def takeEnoughRangesOrNone[T](expectedSize: Int)(ports: Iterator[Option[T]]): Option[Seq[T]] = {
//    val allocatedPorts = ports.takeWhile(_.isDefined).take(expectedSize).flatten.toVector
//    if (allocatedPorts.size == expectedSize) Some(allocatedPorts) else None
//  }
//
//  private[this] lazy val offeredRanges: Seq[Range] = {
//    val rangeIter = for {
//      resource <- offer.getResourcesList.asScala.iterator
//      if acceptedResourceRoles(resource.getRole) && resource.getName == Resource.PORTS
//      rangeInResource <- resource.getRanges.getRangeList.asScala
//      range = Range.inclusive(rangeInResource.getBegin.toInt, rangeInResource.getEnd.toInt)
//    } yield Range(resource.getRole, range)
//    rangeIter.to[Seq]
//  }
//
//  private[this] def shuffledAvailablePorts: Iterator[RangeWithRole] =
//    RangeWithRole.lazyRandomPortsFromRanges(random)(offeredRanges)
//}
//
//object RangesResourceMatcher {
//
//  case class RangeWithRole(role: String, element: Int) {
//    def toRange: protos.Range = {
//      protos.Range(element.toLong, element.toLong)
//    }
//  }
//
//  object RangeWithRole {
//    /**
//      * Return RangesResources covering all given ranges with the given roles.
//      *
//      * Creates as few RangesResources as possible while
//      * preserving the order of the ranges.
//      */
//    def createRangesResources(resources: Seq[RangeWithRole]): Seq[RangesResource] = {
//      /*
//       * Create as few ranges as possible from the given ranges while preserving the order of the ranges.
//       *
//       * It does not check if the given ranges have different roles.
//       */
//      def createRanges(ranges: Seq[RangeWithRole]): Seq[protos.Range] = {
//        val builder = Seq.newBuilder[protos.Range]
//
//        @tailrec
//        def process(lastRangeOpt: Option[protos.Range], next: Seq[RangeWithRole]): Unit = {
//          (lastRangeOpt, next.headOption) match {
//            case (None, _) =>
//            case (Some(lastRange), None) =>
//              builder += lastRange
//            case (Some(lastRange), Some(nextElement)) if lastRange.end == nextElement.element - 1 =>
//              process(Some(lastRange.copy(end = nextElement.element.toLong)), next.tail)
//            case (Some(lastRange), Some(nextElement)) =>
//              builder += lastRange
//              process(Some(nextElement.toRange), next.tail)
//          }
//        }
//        process(ranges.headOption.map(_.toRange), ranges.tail)
//
//        builder.result()
//      }
//
//      val builder = Seq.newBuilder[RangesResource]
//      @tailrec
//      def process(resources: Seq[RangeWithRole]): Unit = resources.headOption match {
//        case None =>
//        case Some(RangeWithRole(role, _)) =>
//          val portsForResource: Seq[RangeWithRole] = resources.takeWhile(_.role == role)
//          builder += RangesResource(name = Resource.PORTS, createRanges(portsForResource), role = role)
//          process(resources.drop(portsForResource.size))
//      }
//      process(resources)
//
//      builder.result()
//    }
//
//    /**
//      * We want to make it less likely that we are reusing the same dynamic port for tasks of different apps.
//      * This way we allow load balancers to reconfigure before reusing the same ports.
//      *
//      * Therefore we want to choose dynamic ports randomly from all the offered port ranges.
//      * We want to use consecutive ports to avoid excessive range fragmentation.
//      *
//      * The implementation idea:
//      *
//      * * Randomize the order of the offered ranges.
//      * * Now treat the ports contained in the ranges as one long sequence of ports.
//      * * We randomly choose an index where we want to start assigning dynamic ports in that sequence. When
//      *   we hit the last offered port with wrap around and start offering the ports at the beginning
//      *   of the sequence up to (excluding) the port index we started at.
//      */
//    def lazyRandomPortsFromRanges(rand: Random = Random)(offeredRanges: Seq[Range]): Iterator[RangeWithRole] = {
//      val numberOfOfferedPorts = offeredRanges.map(_.range.size).sum
//
//      if (numberOfOfferedPorts == 0) {
//        return Iterator.empty
//      }
//
//      def findStartPort(shuffled: Vector[Range], startPortIdx: Int): (Int, Int) = {
//        var startPortIdxOfCurrentRange = 0
//        val rangeIdx = shuffled.indexWhere {
//          case range: Range if startPortIdxOfCurrentRange + range.range.size > startPortIdx =>
//            true
//          case range: Range =>
//            startPortIdxOfCurrentRange += range.range.size
//            false
//        }
//
//        (rangeIdx, startPortIdx - startPortIdxOfCurrentRange)
//      }
//
//      val shuffled = rand.shuffle(offeredRanges).toVector
//      val startPortIdx = rand.nextInt(numberOfOfferedPorts)
//      val (rangeIdx, portInRangeIdx) = findStartPort(shuffled, startPortIdx)
//      val startRangeOrig = shuffled(rangeIdx)
//
//      val startRange = startRangeOrig.withoutNPorts(portInRangeIdx)
//
//      // These are created on demand if necessary
//      def afterStartRange: Iterator[RangeWithRole] =
//        shuffled.slice(rangeIdx + 1, shuffled.length).iterator.flatMap(_.portsWithRolesIterator)
//      def beforeStartRange: Iterator[RangeWithRole] =
//        shuffled.slice(0, rangeIdx).iterator.flatMap(_.portsWithRolesIterator)
//      def endRange: Iterator[RangeWithRole] = startRangeOrig.firstNPorts(portInRangeIdx)
//
//      startRange ++ afterStartRange ++ beforeStartRange ++ endRange
//    }
//  }
//
//  case class Range(role: String, range: Range) {
//    lazy val protoRange: protos.Range = protos.Range(range.start.toLong, range.end.toLong)
//
//    def portsWithRolesIterator: Iterator[RangeWithRole] = range.iterator.map(RangeWithRole(role, _))
//    def firstNPorts(n: Int): Iterator[RangeWithRole] = range.take(n).iterator.map(RangeWithRole(role, _))
//    def withoutNPorts(n: Int): Iterator[RangeWithRole] = range.drop(n).iterator.map(RangeWithRole(role, _))
//  }
//}
//
