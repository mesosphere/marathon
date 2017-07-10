package mesosphere.marathon
package tasks

import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.state.DiskSource
import mesosphere.mesos.protos
import org.apache.mesos.Protos.Resource.DiskInfo.Source
import org.apache.mesos.Protos.Resource.{ DiskInfo, ReservationInfo }
import org.apache.mesos.{ Protos => MesosProtos }
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

object ResourceUtil {
  implicit class RichResource(resource: MesosProtos.Resource) {
    def getDiskSourceOption: Option[Source] =
      if (resource.hasDisk && resource.getDisk.hasSource)
        Some(resource.getDisk.getSource)
      else
        None

    def getStringification: String = {
      require(resource.getName == protos.Resource.DISK)
      val diskSource = DiskSource.fromMesos(getDiskSourceOption)
      /* TODO - make this match mesos stringification */
      (List(
        resource.getName,
        diskSource.diskType.toString,
        resource.getScalar.getValue.toString) ++
        diskSource.path).mkString(":")
    }
  }

  private[this] val log = LoggerFactory.getLogger(getClass)

  /**
    * The resources in launched tasks, should
    * be consumed from resources in the offer with the same [[ResourceMatchKey]].
    */
  private[this] case class ResourceMatchKey(
    role: String, name: String,
    reservation: Option[ReservationInfo], disk: Option[DiskInfo])

  private[this] object ResourceMatchKey {
    def apply(resource: MesosProtos.Resource): ResourceMatchKey = {
      val reservation = if (resource.hasReservation) Some(resource.getReservation) else None
      val disk = if (resource.hasDisk) Some(resource.getDisk) else None
      ResourceMatchKey(resource.getRole, resource.getName, reservation, disk)
    }
  }

  /**
    * Decrements the scalar resource by amount
    *
    */
  def consumeScalarResource(resource: MesosProtos.Resource, amount: Double): Option[MesosProtos.Resource] = {
    require(resource.getType == MesosProtos.Value.Type.SCALAR)
    val isMountDiskResource =
      resource.hasDisk && resource.getDisk.hasSource &&
        (resource.getDisk.getSource.getType == Source.Type.MOUNT)

    // TODO(jdef) would be nice to use fixed precision like Mesos does for scalar math
    val leftOver: Double = resource.getScalar.getValue - amount
    if (leftOver <= 0 || isMountDiskResource) {
      None
    } else {
      Some(resource
        .toBuilder
        .setScalar(
          MesosProtos.Value.Scalar
            .newBuilder().setValue(leftOver))
        .build())
    }
  }

  /**
    * Deduct usedResource from resource. If nothing is left, None is returned.
    */
  def consumeResource(
    resource: MesosProtos.Resource,
    usedResource: MesosProtos.Resource): Option[MesosProtos.Resource] = {
    require(resource.getType == usedResource.getType)

    def deductRange(
      baseRange: MesosProtos.Value.Range,
      usedRange: MesosProtos.Value.Range): Seq[MesosProtos.Value.Range] = {
      if (baseRange.getEnd < usedRange.getBegin || baseRange.getBegin > usedRange.getEnd) {
        // baseRange completely before or after usedRange
        Seq(baseRange)
      } else {
        val rangeBefore: Option[MesosProtos.Value.Range] = if (baseRange.getBegin < usedRange.getBegin)
          Some(baseRange.toBuilder.setEnd(usedRange.getBegin - 1).build())
        else
          None

        val rangeAfter: Option[MesosProtos.Value.Range] = if (baseRange.getEnd > usedRange.getEnd)
          Some(baseRange.toBuilder.setBegin(usedRange.getEnd + 1).build())
        else
          None

        Seq(rangeBefore, rangeAfter).flatten
      }
    }

    def consumeRangeResource: Option[MesosProtos.Resource] = {
      val usedRanges = usedResource.getRanges.getRangeList
      val baseRanges = resource.getRanges.getRangeList

      // FIXME: too expensive?
      val diminished = baseRanges.flatMap { baseRange =>
        usedRanges.foldLeft(Seq(baseRange)) {
          case (result, used) =>
            result.flatMap(deductRange(_, used))
        }
      }

      val rangesBuilder = MesosProtos.Value.Ranges.newBuilder()
      diminished.foreach(rangesBuilder.addRange)

      val result = resource
        .toBuilder
        .setRanges(rangesBuilder)
        .build()

      if (result.getRanges.getRangeCount > 0)
        Some(result)
      else
        None
    }

    def consumeSetResource: Option[MesosProtos.Resource] = {
      val baseSet: Set[String] = resource.getSet.getItemList.toSet
      val consumedSet: Set[String] = usedResource.getSet.getItemList.toSet
      require(consumedSet subsetOf baseSet, s"$consumedSet must be subset of $baseSet")

      val resultSet: Set[String] = baseSet -- consumedSet

      if (resultSet.nonEmpty)
        Some(
          resource
            .toBuilder
            .setSet(MesosProtos.Value.Set.newBuilder().addAllItem(resultSet.asJava))
            .build()
        )
      else
        None
    }

    resource.getType match {
      case MesosProtos.Value.Type.SCALAR => consumeScalarResource(resource, usedResource.getScalar.getValue)
      case MesosProtos.Value.Type.RANGES => consumeRangeResource
      case MesosProtos.Value.Type.SET => consumeSetResource

      case unexpectedResourceType: MesosProtos.Value.Type =>
        log.warn("unexpected resourceType {} for resource {}", Seq(unexpectedResourceType, resource.getName): _*)
        // we don't know the resource, thus we consume it completely
        None
    }
  }

  /**
    * Deduct usedResources from resources by matching them by name and role.
    */
  def consumeResources(
    resources: Seq[MesosProtos.Resource],
    usedResources: Seq[MesosProtos.Resource]): Seq[MesosProtos.Resource] = {
    val usedResourceMap: Map[ResourceMatchKey, Seq[MesosProtos.Resource]] =
      usedResources.groupBy(ResourceMatchKey(_))

    resources.flatMap { resource: MesosProtos.Resource =>
      usedResourceMap.get(ResourceMatchKey(resource)) match {
        case Some(usedResources: Seq[MesosProtos.Resource]) =>
          usedResources.foldLeft(Some(resource): Option[MesosProtos.Resource]) {
            case (Some(resource), usedResource) =>
              if (resource.getType != usedResource.getType) {
                log.warn(
                  "Different resource types for resource {}: {} and {}",
                  resource.getName, resource.getType, usedResource.getType)
                None
              } else
                try ResourceUtil.consumeResource(resource, usedResource)
                catch {
                  case NonFatal(e) =>
                    log.warn("while consuming {} of type {}", resource.getName, resource.getType, e)
                    None
                }

            case (None, _) => None
          }
        case None => // if the resource isn't used, we keep it
          Some(resource)
      }
    }
  }

  /**
    * Deduct usedResources from resources in the offer.
    */
  def consumeResourcesFromOffer(
    offer: MesosProtos.Offer,
    usedResources: Seq[MesosProtos.Resource]): MesosProtos.Offer = {
    val offerResources: Seq[MesosProtos.Resource] = offer.getResourcesList.toSeq
    val leftOverResources = ResourceUtil.consumeResources(offerResources, usedResources)
    offer.toBuilder.clearResources().addAllResources(leftOverResources.asJava).build()
  }

  def displayResource(resource: MesosProtos.Resource, maxRanges: Int): String = {
    def rangesToString(ranges: Seq[MesosProtos.Value.Range]): String = {
      ranges.map { range => s"${range.getBegin}->${range.getEnd}" }.mkString(",")
    }

    lazy val resourceName = {
      val principalString = if (resource.hasReservation && resource.getReservation.hasPrincipal)
        s", RESERVED for ${resource.getReservation.getPrincipal}"
      else
        ""
      val diskString = if (resource.hasDisk && resource.getDisk.hasPersistence)
        s", diskId ${resource.getDisk.getPersistence.getId}"
      else
        ""

      s"${resource.getName}(${resource.getRole}$principalString$diskString)"
    }

    resource.getType match {
      case MesosProtos.Value.Type.SCALAR => s"$resourceName ${resource.getScalar.getValue}"
      case MesosProtos.Value.Type.RANGES =>
        s"$resourceName ${
          val ranges = resource.getRanges.getRangeList.to[Seq]
          if (ranges.size > maxRanges)
            s"${rangesToString(ranges.take(maxRanges))} ... (${ranges.size - maxRanges} more)"
          else
            rangesToString(ranges)
        }"
      case other: MesosProtos.Value.Type => resource.toString
    }
  }

  def displayResources(resources: Seq[MesosProtos.Resource], maxRanges: Int): String = {
    resources.map(displayResource(_, maxRanges)).mkString("; ")
  }
}
