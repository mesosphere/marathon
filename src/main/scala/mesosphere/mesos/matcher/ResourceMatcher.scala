package mesosphere.mesos.matcher

import mesosphere.mesos.ResourceUtil
import mesosphere.marathon.core.launcher.impl.TaskLabels
import mesosphere.marathon.state.{ PersistentVolume, ResourceRole, DiskSource }
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Offer
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.collection

trait ResourceMatcher extends ((String, Iterable[Protos.Resource]) => Iterable[MatchResult]) {
  val resourceName: ResourceMatcher.Role
  def apply(offerId: String, resources: Iterable[Protos.Resource]): Iterable[MatchResult]
}

object ResourceMatcher {
  type Role = String
  type OfferMatcher[T] = (Offer, ResourceSelector) => T

  private[this] val log = LoggerFactory.getLogger(getClass)

  /**
    * Checks whether the given offer contains enough resources to launch a task of the given run spec
    * or to make a reservation for a task.
    *
    * If a task uses local volumes, this method is typically called twice for every launch. Once
    * for the reservation on UNRESERVED resources and once for every (re-)launch on RESERVED resources.
    *
    * If matching on RESERVED resources as specified by the ResourceSelector, resources for volumes
    * have to be matched separately (e.g. by the [[PersistentVolumeMatcher]]). If matching on UNRESERVED
    * resources, the disk resources for the local volumes are included since they must become part of
    * the reservation.
    */
  def matchResources(offer: Offer, matchers: Iterable[ResourceMatcher], selector: ResourceSelector): Option[ResourceMatch] = {
    require(matchers.map(_.resourceName)(collection.breakOut).size == matchers.size, "overlapping matchers not allowed")

    val groupedResources: Map[Role, mutable.Buffer[Protos.Resource]] = offer.getResourcesList.asScala.groupBy(_.getName)
    val offerId = offer.getId.getValue

    val matchResults = matchers.
      flatMap{ matcher => matcher.apply(offerId, groupedResources.getOrElse(matcher.resourceName, Nil)) }.
      filter(_.wantsResources)

    logUnsatisfiedResources(offer, selector, matchResults)

    if (matchResults.forall(_.matches)) {
      Some(ResourceMatch(matchResults))
    } else {
      None
    }
  }

  /**
    * A successful match result of the [[ResourceMatcher]].matchResources method.
    */
  case class ResourceMatch(matches: Iterable[MatchResult]) {
    lazy val hostPorts: Seq[Option[Int]] =
      portsMatch.map(_.hostPorts).getOrElse(Seq(None))

    lazy val scalarMatches = matches.collect {
      case s: ScalarMatch => s
    }

    lazy val portsMatch = matches.collectFirst {
      case p: PortsMatchResult => p
    }

    def scalarMatch(name: String): Option[ScalarMatch] =
      scalarMatches.find(_.resourceName == name)

    def resources: Iterable[Protos.Resource] =
      matches.flatMap(_.consumedResources)

    // TODO - this assumes that volume matches are one resource to one volume, which should be correct, but may not be.
    val localVolumes: Iterable[(DiskSource, PersistentVolume)] =
      matches.collect { case r: DiskResourceMatch => r.volumes }.flatten
  }

  /**
    * Restricts which resources are considered for matching.
    *
    * Disk resources are always discarded, since we do not want to match them by
    * accident.
    *
    * @param acceptedRoles contains all Mesos resource roles that are accepted
    * @param needToReserve if true, only unreserved resources will considered
    * @param labelMatcher a matcher that checks if the given resource labels
    *                     are compliant with the expected or not expected labels
    */
  case class ResourceSelector(
      acceptedRoles: Set[String],
      needToReserve: Boolean,
      labelMatcher: LabelMatcher) {

    def apply(resource: Protos.Resource): Boolean = {
      import ResourceSelector._
      // resources with disks are matched by the VolumeMatcher or not at all
      val noAssociatedVolume = !(resource.hasDisk && resource.getDisk.hasVolume())
      def matchesLabels: Boolean = labelMatcher.matches(reservationLabels(resource))

      noAssociatedVolume && acceptedRoles(resource.getRole) && matchesLabels
    }

    override def toString: String = {
      val reserveString = if (needToReserve) " to reserve" else ""
      val rolesString = acceptedRoles.mkString(", ")
      s"Considering resources$reserveString with roles {$rolesString} $labelMatcher"
    }
  }

  object ResourceSelector {
    /** The reservation labels if the resource is reserved, or an empty Map */
    private def reservationLabels(resource: Protos.Resource): Map[String, String] =
      if (!resource.hasReservation || !resource.getReservation.hasLabels)
        Map.empty
      else {
        import scala.collection.JavaConverters._
        resource.getReservation.getLabels.getLabelsList.asScala.iterator.map { label =>
          label.getKey -> label.getValue
        }.toMap
      }

    /** Match resources with given roles that have at least the given labels */
    def reservedWithLabels(acceptedRoles: Set[String], labels: Map[String, String]): ResourceSelector = {
      ResourceSelector(acceptedRoles, needToReserve = false, LabelMatcher.WithReservationLabels(labels))
    }
    /** Match resources with given roles that do not have known reservation labels */
    def reservable: ResourceSelector = {
      ResourceSelector(Set(ResourceRole.Unreserved), needToReserve = true, LabelMatcher.WithoutReservationLabels)
    }

    /** Match any resources with given roles that do not have known reservation labels */
    def any(acceptedRoles: Set[String]): ResourceSelector = {
      ResourceSelector(acceptedRoles, needToReserve = false, LabelMatcher.WithoutReservationLabels)
    }
  }

  private[mesos] sealed trait LabelMatcher {
    def matches(resourceLabels: Map[String, String]): Boolean
  }

  private[this] object LabelMatcher {
    case class WithReservationLabels(labels: Map[String, String]) extends LabelMatcher {
      override def matches(resourceLabels: Map[String, String]): Boolean =
        labels.forall { case (k, v) => resourceLabels.get(k).contains(v) }

      override def toString: Role = {
        val labelsStr = labels.map { case (k, v) => s"$k: $v" }.mkString(", ")
        s"and labels {$labelsStr}"
      }
    }

    case object WithoutReservationLabels extends LabelMatcher {
      override def matches(resourceLabels: Map[String, String]): Boolean =
        resourceLabels.keys.toSet.intersect(TaskLabels.labelKeysForTaskReservations).isEmpty

      override def toString: Role = "without resident reservation labels"
    }
  }

  /**
    * Given a list of resources, allocates the specified size.
    *
    * Returns an either:
    *
    * - Left:  indicates failure; contains the amount failed to be matched.
    * - Right: indicates success; contains a list of consumptions and a list of resources remaining after the
    *     allocation.
    */
  @tailrec
  private[mesos] def consumeResources(
    valueLeft: Double,
    resourcesLeft: List[Protos.Resource],
    resourcesNotConsumed: List[Protos.Resource] = Nil,
    resourcesConsumed: List[GeneralScalarMatch.Consumption] = Nil,
    matcher: Protos.Resource => Boolean = { _ => true }):
      // format: OFF
      Either[(Double), (List[GeneralScalarMatch.Consumption], List[Protos.Resource])] = {
    // format: ON
    if (valueLeft <= 0) {
      Right((resourcesConsumed, resourcesLeft ++ resourcesNotConsumed))
    } else {
      resourcesLeft match {
        case Nil => Left(valueLeft)

        case nextResource :: restResources =>
          if (matcher(nextResource)) {
            val consume = Math.min(valueLeft, nextResource.getScalar.getValue)
            val decrementedResource = ResourceUtil.subtractScalarValue(nextResource, consume)
            val newValueLeft = valueLeft - consume
            val reservation = if (nextResource.hasReservation) Option(nextResource.getReservation) else None
            val consumedValue = GeneralScalarMatch.Consumption(consume, nextResource.getRole, reservation)

            consumeResources(newValueLeft, restResources, (decrementedResource ++ resourcesNotConsumed).toList,
              consumedValue :: resourcesConsumed, matcher)
          } else {
            consumeResources(
              valueLeft, restResources, nextResource :: resourcesNotConsumed, resourcesConsumed, matcher)
          }
      }
    }
  }

  private[this] def logUnsatisfiedResources(
    offer: Offer,
    selector: ResourceSelector,
    matchResults: Iterable[MatchResult]): Unit = {
    if (log.isInfoEnabled && matchResults.exists(!_.matches)) {
      val basicResourceString = matchResults.mkString(", ")
      log.info(
        s"Offer [${offer.getId.getValue}]. " +
          s"$selector. " +
          s"Not all basic resources satisfied: $basicResourceString")
    }
  }
}
