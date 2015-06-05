package mesosphere.marathon.state

import org.apache.mesos.Protos.{ Value, Resource }
import org.apache.log4j.Logger
import mesosphere.mesos.protos.Range
import scala.collection.JavaConverters._

case class CustomResource(
    name: String = CustomResource.DefaultName,

    scalar: Option[Double] = CustomResource.DefaultScalar,

    ranges: Option[Seq[Value.Range]] = CustomResource.DefaultRange,

    set: Option[Set[String]] = CustomResource.DefaultSet) {

  val log = Logger.getLogger(getClass.getName)

  val resourceSet = Set(scalar, ranges, set)

  if (resourceSet.filter(!_.isEmpty).size == 0) {
    log.info("No resource value (scalar, range, or set) specified for custom resource") //TODOC change to warn
  }

  if (resourceSet.filter(!_.isEmpty).size > 1) {
    log.info("Multiple resources types specified for (scalar, range, or set) specified" +
      " for custom resource")
  }

  var resourceType: Value.Type = Value.Type.SCALAR
  if (!ranges.isEmpty)
    resourceType = Value.Type.RANGES
  else if (!set.isEmpty)
    resourceType = Value.Type.SET

  def getType: Value.Type = resourceType
}

object CustomResource {
  //val standardResources = Set(Resource.CPUS, Resource.MEM, Resource.DISK, Resource.PORTS)
  //TODOC throw error if standardResource?
  val log = Logger.getLogger(getClass.getName)

  val DefaultName: String = ""

  val DefaultScalar: Option[Double] = None

  val DefaultRange: Option[Seq[Value.Range]] = None

  val DefaultSet: Option[Set[String]] = None

  def create(resource: Resource): Option[CustomResource] = {
    resource.getType match {
      case Value.Type.SCALAR =>
        Some(CustomResource(resource.getName, scalar = Some(resource.getScalar.getValue: Double)))
      case Value.Type.RANGES =>
        Some(CustomResource(resource.getName, ranges =
          Some(resource.getRanges.getRangeList.asScala.toSeq: Seq[Value.Range])))
      case Value.Type.SET =>
        Some(CustomResource(resource.getName, set = Some(resource.getSet.getItemList.asScala.toSet: Set[String])))
      case default =>
        None
    }
  }
}

