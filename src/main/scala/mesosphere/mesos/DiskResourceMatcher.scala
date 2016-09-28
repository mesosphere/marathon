package mesosphere.mesos

import mesosphere.marathon.state.{ PersistentVolume, DiskType, DiskSource }
import mesosphere.mesos.protos.Resource
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Resource.DiskInfo.Source
import mesosphere.mesos.ResourceMatcher.{ ResourceSelector, consumeResources }
import scala.annotation.tailrec
import scala.collection.immutable

/**
  * Match volumes against disk resources and return results which keep disk sources and persistentVolumes associated.
  *
  * TODO - handle matches for a single volume across multiple resource offers for the same disk
  */
class DiskResourceMatcher(
    selector: ResourceSelector,
    scratchDisk: Double,
    volumes: Iterable[PersistentVolume],
    scope: ScalarMatchResult.Scope = ScalarMatchResult.Scope.NoneDisk) extends ResourceMatcher {

  val resourceName = Resource.DISK

  import ResourceUtil.RichResource
  private[mesos] case class SourceResources(source: Option[Source], resources: List[Protos.Resource]) {
    lazy val size = resources.foldLeft(0.0)(_ + _.getScalar.getValue)
  }
  private[mesos] object SourceResources extends ((Option[Source], List[Protos.Resource]) => SourceResources) {
    def listFromResources(l: List[Protos.Resource]): List[SourceResources] = {
      l.groupBy(_.diskSourceOption).map(SourceResources.tupled).toList
    }
  }

  /*
   Prioritize resources to make the most sensible allocation.
   - If requesting full disk, allocate the smallest disk volume that meets constraints
   - If requesting root, just return it because there can only be one.
   - If requesting path disk, allocate the largest volume possible to spread allocation evenly.
   This may not be ideal if you'd prefer to leave room for larger allocations instead.

   TODO - test. Also, parameterize?
   */
  private def prioritizeDiskResources(
    diskType: DiskType,
    resources: List[SourceResources]): List[SourceResources] = {
    diskType match {
      case DiskType.Root =>
        resources
      case DiskType.Path =>
        resources.sortBy(_.size)(implicitly[Ordering[Double]].reverse)
      case DiskType.Mount =>
        resources.sortBy(_.size)
    }
  }

  // format: OFF
  @tailrec
  private def findDiskGroupMatches(
    requiredValue: Double,
    resourcesRemaining: List[SourceResources],
    allSourceResources: List[SourceResources],
    matcher: Protos.Resource => Boolean):
      Option[(Option[Source], List[GeneralScalarMatch.Consumption], List[SourceResources])] =
    // format: ON
    resourcesRemaining match {
      case Nil =>
        None
      case next :: rest =>
        consumeResources(requiredValue, next.resources, matcher = matcher) match {
          case Left(_) =>
            findDiskGroupMatches(requiredValue, rest, allSourceResources, matcher)
          case Right((resourcesConsumed, remainingAfterConsumption)) =>
            val sourceResourcesAfterConsumption = if (remainingAfterConsumption.isEmpty)
              None
            else
              Some(next.copy(resources = remainingAfterConsumption))

            Some((
              next.source,
              resourcesConsumed,
              (sourceResourcesAfterConsumption ++ (allSourceResources.filterNot(_ == next))).toList))
        }
    }

  @tailrec
  private def findMatches(
    diskType: DiskType,
    pendingAllocations: List[Either[Double, PersistentVolume]],
    resourcesRemaining: List[SourceResources],
    resourcesConsumed: List[DiskResourceMatch.Consumption] = Nil): Either[DiskResourceNoMatch, DiskResourceMatch] = {
    val orderedResources = prioritizeDiskResources(diskType, resourcesRemaining)

    pendingAllocations match {
      case Nil =>
        Right(DiskResourceMatch(diskType, resourcesConsumed, scope))
      case nextAllocation :: restAllocations =>
        val (matcher, nextAllocationSize) = nextAllocation match {
          case Left(size) => ({ _: Protos.Resource => true }, size)
          case Right(v) => (
            VolumeConstraints.meetsAllConstraints(_: Protos.Resource, v.persistent.constraints),
            v.persistent.size.toDouble
          )
        }

        findDiskGroupMatches(nextAllocationSize, orderedResources, orderedResources, matcher) match {
          case None =>
            Left(
              DiskResourceNoMatch(resourcesConsumed, resourcesRemaining.flatMap(_.resources), nextAllocation, scope))
          case Some((source, generalConsumptions, decrementedResources)) =>
            val consumptions = generalConsumptions.map { c =>
              DiskResourceMatch.Consumption(c, source, nextAllocation.right.toOption)
            }

            findMatches(
              diskType,
              restAllocations,
              decrementedResources,
              consumptions ++ resourcesConsumed)
        }
    }
  }

  /*
   * The implementation for finding mount matches differs from disk matches because:
   * - A mount volume cannot be partially allocated. The resource allocation request must be sized up to match the
   *   actual resource size
   * - The mount volume can't be split amongst reserved / non-reserved.
   * - The mount volume has an extra maxSize concern
   *
   * If this method can be generalized to worth with the above code, then so be it.
   */
  @tailrec private def findMountMatches(
    pendingAllocations: List[PersistentVolume],
    resources: List[Protos.Resource],
    resourcesConsumed: List[DiskResourceMatch.Consumption] = Nil): Either[DiskResourceNoMatch, DiskResourceMatch] = {
    pendingAllocations match {
      case Nil =>
        Right(DiskResourceMatch(DiskType.Mount, resourcesConsumed, scope))
      case nextAllocation :: restAllocations =>
        resources.find { resource =>
          val resourceSize = resource.getScalar.getValue
          VolumeConstraints.meetsAllConstraints(resource, nextAllocation.persistent.constraints) &&
            (resourceSize >= nextAllocation.persistent.size) &&
            (resourceSize <= nextAllocation.persistent.maxSize.getOrElse(Long.MaxValue))
        } match {
          case Some(matchedResource) =>
            val consumedAmount = matchedResource.getScalar.getValue
            val grownVolume =
              nextAllocation.copy(
                persistent = nextAllocation.persistent.copy(
                  size = consumedAmount.toLong))
            val consumption =
              DiskResourceMatch.Consumption(
                consumedAmount,
                role = matchedResource.getRole,
                reservation = if (matchedResource.hasReservation) Option(matchedResource.getReservation) else None,
                source = DiskSource.fromMesos(matchedResource.diskSourceOption),
                Some(grownVolume))

            findMountMatches(
              restAllocations,
              resources.filterNot(_ == matchedResource),
              consumption :: resourcesConsumed)
          case None =>
            Left(DiskResourceNoMatch(resourcesConsumed, resources, Right(nextAllocation), scope))
        }
    }
  }

  def apply(offerId: String, diskResources: Iterable[Protos.Resource]): Iterable[MatchResult] = {
    val resourcesByType: immutable.Map[DiskType, Iterable[Protos.Resource]] = diskResources.groupBy { r =>
      DiskSource.fromMesos(r.diskSourceOption).diskType
    }.withDefault(_ => Nil)

    val scratchDiskRequest = if (scratchDisk > 0.0) Some(Left(scratchDisk)) else None
    val requestedResourcesByType: immutable.Map[DiskType, Iterable[Either[Double, PersistentVolume]]] =
      (scratchDiskRequest ++ volumes.map(Right(_)).toList).groupBy {
        case Left(_) => DiskType.Root
        case Right(p) => p.persistent.`type`
      }

    val matchResults: Iterable[Either[ScalarMatchResult, ScalarMatchResult]] =
      requestedResourcesByType.keys.map { diskType =>
        val withBiggestRequestsFirst =
          requestedResourcesByType(diskType).
            toList.
            sortBy({ r => r.right.map(_.persistent.size.toDouble).merge })(implicitly[Ordering[Double]].reverse)

        val resources = resourcesByType(diskType).filter(selector(_)).toList

        if (diskType == DiskType.Mount) {
          findMountMatches(
            withBiggestRequestsFirst.flatMap(_.right.toOption),
            resources)
        } else
          findMatches(
            diskType,
            withBiggestRequestsFirst,
            SourceResources.listFromResources(resources))
      }
    matchResults.map(_.merge)
  }
}
