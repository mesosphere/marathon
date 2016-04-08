package mesosphere.mesos

import mesosphere.marathon.core.launcher.impl.ResourceLabels
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ AppDefinition, ResourceRole }
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
    lazy val hostPorts: Seq[Int] = portsMatch.hostPorts

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
    * @param reserved if reserved is true, only resources with a ReservationInfo
    *                 are considered. If reserved is false, only resources without
    *                 a ReservationInfo are considered.
    * @param requiredLabels only resources with the given keys/values are matched.
    */
  case class ResourceSelector(
      acceptedRoles: Set[String], reserved: Boolean, requiredLabels: ResourceLabels = ResourceLabels.empty) {
    def apply(resource: Protos.Resource): Boolean = {
      // resources with disks are matched by the VolumeMatcher or not at all
      val noAssociatedDisk = !resource.hasDisk
      def hasRequiredLabels: Boolean = {
        val labelMap: Map[String, String] =
          if (!resource.hasReservation || !resource.getReservation.hasLabels)
            Map.empty
          else {
            import scala.collection.JavaConverters._
            resource.getReservation.getLabels.getLabelsList.asScala.iterator.map { label =>
              label.getKey -> label.getValue
            }.toMap
          }
        requiredLabels.labels.forall { case (k, v) => labelMap.get(k).contains(v) }
      }

      noAssociatedDisk && acceptedRoles(resource.getRole) && resource.hasReservation == reserved && hasRequiredLabels
    }

    override def toString: String = {
      val reservedString = if (reserved) "RESERVED" else "unreserved"
      val rolesString = acceptedRoles.mkString(", ")
      val labelStrings = if (requiredLabels.labels.nonEmpty) s" and labels $requiredLabels" else ""
      s"Considering $reservedString resources with roles {$rolesString}$labelStrings"
    }
  }

  object ResourceSelector {
    /** Match unreserved resources for which role == '*' applies (default) */
    def wildcard: ResourceSelector = ResourceSelector(Set(ResourceRole.Unreserved), reserved = false)
  }

  /**
    * Checks whether the given offer contains enough resources to launch a task of the given app
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
  def matchResources(offer: Offer, app: AppDefinition, runningTasks: => Iterable[Task],
                     selector: ResourceSelector): Option[ResourceMatch] = {

    val groupedResources: Map[Role, mutable.Buffer[Protos.Resource]] = offer.getResourcesList.asScala.groupBy(_.getName)

    val scalarResourceMatch = matchScalarResource(groupedResources, selector) _

    // Local volumes only need to be matched if we are making a reservation for resident tasks --
    // that means if the resources that are matched are still unreserved.
    val diskMatch = if (!selector.reserved && app.diskForPersistentVolumes > 0)
      scalarResourceMatch(Resource.DISK, app.disk + app.diskForPersistentVolumes,
        ScalarMatchResult.Scope.IncludingLocalVolumes)
    else
      scalarResourceMatch(Resource.DISK, app.disk, ScalarMatchResult.Scope.ExcludingLocalVolumes)

    val scalarMatchResults = Iterable(
      scalarResourceMatch(Resource.CPUS, app.cpus, ScalarMatchResult.Scope.NoneDisk),
      scalarResourceMatch(Resource.MEM, app.mem, ScalarMatchResult.Scope.NoneDisk),
      diskMatch
    ).filter(_.requiredValue != 0)

    logUnsatisfiedResources(offer, selector, scalarMatchResults)

    def portsMatchOpt: Option[PortsMatch] = new PortsMatcher(app, offer, selector).portsMatch

    def meetsAllConstraints: Boolean = {
      lazy val tasks = runningTasks.filter(_.launched.exists(_.appVersion >= app.versionInfo.lastConfigChangeVersion))
      val badConstraints = app.constraints.filterNot { constraint =>
        Constraints.meetsConstraint(tasks, offer, constraint)
      }

      if (badConstraints.nonEmpty && log.isInfoEnabled) {
        log.info(
          s"Offer [${offer.getId.getValue}]. Constraints for app [${app.id}] not satisfied.\n" +
            s"The conflicting constraints are: [${badConstraints.mkString(", ")}]"
        )
      }

      badConstraints.isEmpty
    }

    if (scalarMatchResults.forall(_.matches)) {
      for {
        portsMatch <- portsMatchOpt
        if meetsAllConstraints
      } yield ResourceMatch(scalarMatchResults.collect { case m: ScalarMatch => m }, portsMatch)
    }
    else {
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
      }
      else {
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

  private[this] def logUnsatisfiedResources(offer: Offer,
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
