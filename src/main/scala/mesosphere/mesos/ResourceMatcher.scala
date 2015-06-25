package mesosphere.mesos

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.PortsMatcher
import mesosphere.marathon.tasks.PortsMatcher.PortWithRole
import mesosphere.mesos.protos.{ ScalarResource, SetResource, RangesResource, Resource }
import mesosphere.marathon.state.CustomResource.{ CustomRange }
import org.apache.log4j.Logger
import org.apache.mesos.Protos.{ Offer, Value }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

object ResourceMatcher {
  type Role = String

  private[this] val log = Logger.getLogger(getClass)

  case class ResourceMatch(cpuRole: Role, memRole: Role, diskRole: Role, ports: Seq[RangesResource],
                           customScalars: Seq[ScalarResource], customSets: Seq[SetResource],
                           customRanges: Seq[RangesResource])

  //scalastyle:off cyclomatic.complexity
  //scalastyle:off method.length
  def matchResources(offer: Offer, app: AppDefinition, runningTasks: => Set[MarathonTask],
                     acceptedResourceRoles: Set[String] = Set("*")): Option[ResourceMatch] = {
    val groupedResources = offer.getResourcesList.asScala.groupBy(_.getName)

    def findScalarResourceRole(tpe: String, value: Double): Option[Role] =
      groupedResources.get(tpe).flatMap {
        _
          .filter(resource => acceptedResourceRoles(resource.getRole))
          .find { resource =>
            resource.getScalar.getValue >= value
          }.map(_.getRole)
      }

    def findCustomScalar(tpe: String, value: Double): Option[ScalarResource] =
      groupedResources.get(tpe).flatMap {
        _
          .filter(resource => acceptedResourceRoles(resource.getRole))
          .find { resource =>
            resource.getScalar.getValue >= value
          }.map { r =>
            ScalarResource(tpe, value, r.getRole)
          }
      }

    def findCustomSet(tpe: String, value: Set[String], numberRequired: Int): Option[SetResource] =
      groupedResources.get(tpe).flatMap {
        _
          .filter(resource => acceptedResourceRoles(resource.getRole))
          .map { resource =>
            val subset = value & resource.getSet.getItemList.asScala.toSet
            if (subset.size >= numberRequired) {
              Some(SetResource(tpe, subset.take(numberRequired), resource.getRole))
            }
            else
              None
          }
          .flatten
          .headOption
      }
    def findCustomRanges(tpe: String, value: Seq[CustomRange]): Option[RangesResource] =
      groupedResources.get(tpe).flatMap {
        _
          .filter(resource => acceptedResourceRoles(resource.getRole)) // TODOC test what you get at this stage
          .map { resource =>
            var availableRanges = collection.SortedSet(
              resource.getRanges.getRangeList.asScala.flatMap(r => (r.getBegin to r.getEnd)): _*)
            var totalRequired: Long = 0
            var success = true
            var resourcesTaken = value.flatMap { range =>
              totalRequired += range.numberRequired
              val subset = availableRanges.intersect((range.begin.get to range.end.get).toSet)
              if (subset.size >= range.numberRequired) {
                val taken = subset.take(range.numberRequired.toInt).toList // TODOC need to take Long ideally...
                availableRanges --= taken
                taken
                // takes from the left of the TreeSet, ensuring ranges aren't modified too much
              }
              else {
                success = false
                List.empty
              }
            }.map { r => PortWithRole(resource.getRole, r.toInt) } // TODOC find out convert to double
            if (success) {
              Some(RangesResource(tpe, PortWithRole.createPortsResources(resourcesTaken).flatMap { r =>
                r.ranges
              }, resource.getRole))
            }
            else {
              None
            }
          }
          .flatten
          .headOption
      }
    def cpuRoleOpt: Option[Role] = findScalarResourceRole(Resource.CPUS, app.cpus)
    def memRoleOpt: Option[Role] = findScalarResourceRole(Resource.MEM, app.mem)
    def diskRoleOpt: Option[Role] = findScalarResourceRole(Resource.DISK, app.disk)

    def customScalarRolesOpt: Option[Seq[ScalarResource]] = Some(app.customResources
      .filter {
        case (_, value) =>
          value.getType == Value.Type.SCALAR
      }
      .map {
        case (key, value) =>
          findCustomScalar(key, value.scalar.get.value)
      }.flatten.toList)

    def customRangesRolesOpt: Option[Seq[RangesResource]] = Some(app.customResources
      .filter {
        case (_, value) =>
          value.getType == Value.Type.RANGES
      }
      .map {
        case (key, value) =>
          findCustomRanges(key, value.ranges.get.value.toList)
      }
      .flatten.toList)

    def customSetRolesOpt: Option[Seq[SetResource]] = Some(app.customResources
      .filter {
        case (_, value) =>
          value.getType == Value.Type.SET
      }
      .map {
        case (key, value) =>
          findCustomSet(key, value.set.get.value, value.set.get.numberRequired)
      }
      .flatten.toList)

    def customResourcesFulfilled: Boolean = if (customScalarRolesOpt.get.size + customSetRolesOpt.get.size +
      customRangesRolesOpt.get.size == app.customResources.size)
      true
    else
      false

    def meetsAllConstraints: Boolean = {
      lazy val tasks = runningTasks
      val badConstraints = app.constraints.filterNot { constraint =>
        Constraints.meetsConstraint(tasks, offer, constraint)
      }

      if (badConstraints.nonEmpty) {
        log.warn(
          s"Offer did not satisfy constraints for app [${app.id}].\n" +
            s"Conflicting constraints are: [${badConstraints.mkString(", ")}]"
        )
      }

      badConstraints.isEmpty
    }

    def portsOpt: Option[Seq[RangesResource]] = new PortsMatcher(app, offer, acceptedResourceRoles).portRanges match {
      case None =>
        log.warn("App ports are not available in the offer.")
        None

      case x @ Some(portRanges) =>
        log.debug("Met all constraints.")
        x
    }

    for {
      cpuRole <- cpuRoleOpt
      memRole <- memRoleOpt
      diskRole <- diskRoleOpt
      portRanges <- portsOpt
      if meetsAllConstraints && customResourcesFulfilled
    } yield ResourceMatch(cpuRole, memRole, diskRole, portRanges, customScalarRolesOpt.get, customSetRolesOpt.get,
      customRangesRolesOpt.get)
  }
}
