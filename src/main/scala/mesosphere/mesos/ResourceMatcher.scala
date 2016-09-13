package mesosphere.mesos

import mesosphere.marathon.core.launcher.impl.TaskLabels
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ RunSpec, ResourceRole }
import mesosphere.marathon.tasks.{ PortsMatch, PortsMatcher }
import mesosphere.mesos.protos.Resource
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Offer
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.collection.mutable

object ResourceMatcher {
  type Role = String

  private[this] val log = LoggerFactory.getLogger(getClass)

  /**
    * A successful match result of the [[ResourceMatcher]].matchResources method.
    */
  case class ResourceMatch(scalarMatches: Iterable[ScalarMatch], portsMatch: PortsMatch) {
    lazy val hostPorts: Seq[Option[Int]] = portsMatch.hostPorts

    def scalarMatch(name: String): Option[ScalarMatch] = scalarMatches.find(_.resourceName == name)

    def resources: Iterable[org.apache.mesos.Protos.Resource] =
      scalarMatches.flatMap(_.consumedResources) ++ portsMatch.resources
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
      val noAssociatedDisk = !resource.hasDisk
      def matchesLabels: Boolean = labelMatcher.matches(reservationLabels(resource))

      noAssociatedDisk && acceptedRoles(resource.getRole) && matchesLabels
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
  def matchResources(offer: Offer, runSpec: RunSpec, runningTasks: => Iterable[Task],
    selector: ResourceSelector): Option[ResourceMatch] = {

    val groupedResources: Map[Role, mutable.Buffer[Protos.Resource]] = offer.getResourcesList.asScala.groupBy(_.getName)

    val scalarResourceMatch = matchScalarResource(groupedResources, selector) _

    // Local volumes only need to be matched if we are making a reservation for resident tasks --
    // that means if the resources that are matched are still unreserved.
    def needToReserveDisk = selector.needToReserve && runSpec.diskForPersistentVolumes > 0
    val diskMatch = if (needToReserveDisk)
      scalarResourceMatch(Resource.DISK, runSpec.disk + runSpec.diskForPersistentVolumes,
        ScalarMatchResult.Scope.IncludingLocalVolumes)
    else
      scalarResourceMatch(Resource.DISK, runSpec.disk, ScalarMatchResult.Scope.ExcludingLocalVolumes)

    val scalarMatchResults = Iterable(
      scalarResourceMatch(Resource.CPUS, runSpec.cpus, ScalarMatchResult.Scope.NoneDisk),
      scalarResourceMatch(Resource.MEM, runSpec.mem, ScalarMatchResult.Scope.NoneDisk),
      diskMatch,
      scalarResourceMatch(Resource.GPUS, runSpec.gpus.toDouble, ScalarMatchResult.Scope.NoneDisk)
    ).filter(_.requiredValue != 0)

    logUnsatisfiedResources(offer, selector, scalarMatchResults)

    def portsMatchOpt: Option[PortsMatch] = PortsMatcher(runSpec, offer, selector).portsMatch

    def meetsAllConstraints: Boolean = {
      lazy val tasks =
        runningTasks.filter(_.launched.exists(_.runSpecVersion >= runSpec.versionInfo.lastConfigChangeVersion))
      val badConstraints = runSpec.constraints.filterNot { constraint =>
        Constraints.meetsConstraint(tasks, offer, constraint)
      }

      if (badConstraints.nonEmpty && log.isInfoEnabled) {
        log.info(
          s"Offer [${offer.getId.getValue}]. Constraints for run spec [${runSpec.id}] not satisfied.\n" +
            s"The conflicting constraints are: [${badConstraints.mkString(", ")}]"
        )
      }

      badConstraints.isEmpty
    }

    if (scalarMatchResults.forall(_.matches) && meetsAllConstraints) {
      portsMatchOpt.map { portsMatch =>
        ResourceMatch(scalarMatchResults.collect { case m: ScalarMatch => m }, portsMatch)
      }
    } else {
      None
    }
  }

  private[this] def matchScalarResource(
    groupedResources: Map[Role, mutable.Buffer[Protos.Resource]], selector: ResourceSelector)(
    name: String, requiredValue: Double,
    scope: ScalarMatchResult.Scope = ScalarMatchResult.Scope.NoneDisk): ScalarMatchResult = {

    require(scope == ScalarMatchResult.Scope.NoneDisk || name == Resource.DISK)

    @tailrec
    def findMatches(
      valueLeft: Double,
      resourcesLeft: Iterable[Protos.Resource],
      resourcesConsumed: List[ScalarMatch.Consumption] = List.empty): ScalarMatchResult = {
      if (valueLeft <= 0) {
        ScalarMatch(name, requiredValue, resourcesConsumed, scope = scope)
      } else {
        resourcesLeft.headOption match {
          case None => NoMatch(name, requiredValue, requiredValue - valueLeft, scope = scope)
          case Some(nextResource) =>
            val consume = Math.min(valueLeft, nextResource.getScalar.getValue)
            val newValueLeft = valueLeft - consume
            val reservation = if (nextResource.hasReservation) Option(nextResource.getReservation) else None
            val consumedValue = ScalarMatch.Consumption(consume, nextResource.getRole, reservation)
            findMatches(newValueLeft, resourcesLeft.tail, consumedValue :: resourcesConsumed)
        }
      }
    }

    val resourcesForName = groupedResources.getOrElse(name, Iterable.empty)
    val matchingScalarResources = resourcesForName.filter(selector(_))
    findMatches(requiredValue, matchingScalarResources)
  }

  private[this] def logUnsatisfiedResources(
    offer: Offer,
    selector: ResourceSelector,
    scalarMatchResults: Iterable[ScalarMatchResult]): Unit = {
    if (log.isInfoEnabled) {
      if (scalarMatchResults.exists(!_.matches)) {
        val basicResourceString = scalarMatchResults.mkString(", ")
        log.info(
          s"Offer [${offer.getId.getValue}]. " +
            s"$selector. " +
            s"Not all basic resources satisfied: $basicResourceString")
      }
    }
  }
}
