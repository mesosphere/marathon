package mesosphere.marathon.tasks

import com.twitter.util.NonFatal
import org.apache.mesos.Protos.{ Value, Resource, Offer }
import org.slf4j.LoggerFactory
import scala.collection.JavaConverters._

object ResourceUtil {

  private[this] val log = LoggerFactory.getLogger(getClass)

  /**
    * The resources in launched tasks, should
    * be consumed from resources in the offer with the same [[ResourceMatchKey]].
    */
  private[this] case class ResourceMatchKey(role: String, name: String)
  private[this] object ResourceMatchKey {
    def apply(resource: Resource): ResourceMatchKey = ResourceMatchKey(resource.getRole, resource.getName)
  }

  /**
    * Deduct usedResource from resource. If nothing is left, None is returned.
    */
  //TODO: fix style issue and enable this scalastyle check
  //scalastyle:off cyclomatic.complexity method.length
  def consumeResource(resource: Resource, usedResource: Resource): Option[Resource] = {
    require(resource.getType == usedResource.getType)

    def consumeScalarResource: Option[Resource] = {
      val leftOver: Double = resource.getScalar.getValue - usedResource.getScalar.getValue
      if (leftOver <= 0) {
        None
      }
      else {
        Some(resource
          .toBuilder
          .setScalar(
            Value.Scalar
              .newBuilder().setValue(leftOver))
          .build())
      }
    }

    def deductRange(baseRange: Value.Range, usedRange: Value.Range): Seq[Value.Range] = {
      if (baseRange.getEnd < usedRange.getBegin) { // baseRange completely before usedRange
        Seq(baseRange)
      }
      else if (baseRange.getBegin > usedRange.getEnd) { // baseRange completely after usedRange
        Seq(baseRange)
      }
      else {
        val rangeBefore: Option[Value.Range] = if (baseRange.getBegin < usedRange.getBegin)
          Some(baseRange.toBuilder.setEnd(usedRange.getBegin - 1).build())
        else
          None

        val rangeAfter: Option[Value.Range] = if (baseRange.getEnd > usedRange.getEnd)
          Some(baseRange.toBuilder.setBegin(usedRange.getEnd + 1).build())
        else
          None

        Seq(rangeBefore, rangeAfter).flatten
      }
    }

    def consumeRangeResource: Option[Resource] = {
      val usedRanges = usedResource.getRanges.getRangeList.asScala
      val baseRanges = resource.getRanges.getRangeList.asScala

      // FIXME: too expensive?
      val diminished = baseRanges.flatMap { baseRange =>
        usedRanges.foldLeft(Seq(baseRange)) {
          case (result, used) =>
            result.flatMap(deductRange(_, used))
        }
      }

      val rangesBuilder = Value.Ranges.newBuilder()
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

    def consumeSetResource: Option[Resource] = {
      val baseSet: Set[String] = resource.getSet.getItemList.asScala.toSet
      val consumedSet: Set[String] = usedResource.getSet.getItemList.asScala.toSet
      require(consumedSet subsetOf baseSet, s"$consumedSet must be subset of $baseSet")

      val resultSet: Set[String] = baseSet -- consumedSet

      if (resultSet.nonEmpty)
        Some(
          resource
            .toBuilder
            .setSet(Value.Set.newBuilder().addAllItem(resultSet.asJava))
            .build()
        )
      else
        None
    }

    resource.getType match {
      case Value.Type.SCALAR => consumeScalarResource
      case Value.Type.RANGES => consumeRangeResource
      case Value.Type.SET    => consumeSetResource

      case unexpectedResourceType: Value.Type =>
        log.warn("unexpected resourceType {} for resource {}", Seq(unexpectedResourceType, resource.getName): _*)
        // we don't know the resource, thus we consume it completely
        None
    }
  }

  /**
    * Deduct usedResources from resources by matching them by name and role.
    */
  def consumeResources(resources: Iterable[Resource], usedResources: Iterable[Resource]): Iterable[Resource] = {
    val usedResourceMap: Map[ResourceMatchKey, Seq[Resource]] =
      usedResources.groupBy(ResourceMatchKey(_)).mapValues(_.to[Seq])

    resources.flatMap { resource: Resource =>
      usedResourceMap.get(ResourceMatchKey(resource)) match {
        case Some(usedResources: Seq[Resource]) =>
          usedResources.foldLeft(Some(resource): Option[Resource]) {
            case (Some(resource), usedResource) =>
              if (resource.getType != usedResource.getType) {
                log.warn("Different resource types for resource {}: {} and {}",
                  resource.getName, resource.getType, usedResource.getType)
                None
              }
              else
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
  def consumeResourcesFromOffer(offer: Offer, usedResources: Iterable[Resource]): Offer = {
    import scala.collection.JavaConverters._
    val offerResources: Seq[Resource] = offer.getResourcesList.asScala
    val leftOverResources = ResourceUtil.consumeResources(offerResources, usedResources)
    offer.toBuilder.clearResources().addAllResources(leftOverResources.asJava).build()
  }

  def displayResource(resource: Resource, maxRanges: Int): String = {
    def rangesToString(ranges: Seq[Value.Range]): String = {
      ranges.map { range => s"${range.getBegin}->${range.getEnd}" }.mkString(",")
    }

    resource.getType match {
      case Value.Type.SCALAR => s"${resource.getName} ${resource.getScalar.getValue}"
      case Value.Type.RANGES =>
        s"${resource.getName} ${
          val ranges = resource.getRanges.getRangeList.asScala
          if (ranges.size > maxRanges)
            s"${rangesToString(ranges.take(maxRanges))} ... (${ranges.size - maxRanges} more)"
          else
            rangesToString(ranges)
        }"
      case other: Value.Type => resource.toString
    }
  }

  def displayResources(resources: Iterable[Resource], maxRanges: Int): String = {
    resources.map(displayResource(_, maxRanges)).mkString("; ")
  }
}
