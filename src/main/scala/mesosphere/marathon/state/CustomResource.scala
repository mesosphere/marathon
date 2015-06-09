package mesosphere.marathon.state

import org.apache.mesos.Protos.{ Value }
import mesosphere.marathon.Protos
import org.apache.log4j.Logger
import mesosphere.mesos.protos.Range
import scala.collection.JavaConverters._

case class CustomResource(
    scalar: Option[CustomResource.CustomScalarResource] = None,

    ranges: Option[CustomResource.CustomRangeResource] = None,

    set: Option[CustomResource.CustomSetResource] = None) {

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

  def toProto: Protos.CustomResourceDefinition = {
    val builder = Protos.CustomResourceDefinition.newBuilder

    getType match {
      case Value.Type.SCALAR => builder.setScalar(scalar.toProto)
      case Value.Type.RANGES => builder.setRange(ranges.toProto)
      case Value.Type.SET    => builder.setSet(set.toProto)
      case _                 => ;
    }

    builder.build
  }
  /*
  def toProto: Protos.HealthCheckDefinition = {
    val builder = Protos.HealthCheckDefinition.newBuilder
      .setProtocol(this.protocol)
      .setPortIndex(this.portIndex)
      .setGracePeriodSeconds(this.gracePeriod.toSeconds.toInt)
      .setIntervalSeconds(this.interval.toSeconds.toInt)
      .setTimeoutSeconds(this.timeout.toSeconds.toInt)
      .setMaxConsecutiveFailures(this.maxConsecutiveFailures)
      .setIgnoreHttp1Xx(this.ignoreHttp1xx)

    command foreach { c => builder.setCommand(c.toProto) }

    path foreach builder.setPath

    builder.build
  }
    */
}

object CustomResource {
  //val standardResources = Set(Resource.CPUS, Resource.MEM, Resource.DISK, Resource.PORTS)
  //TODOC throw error if standardResource?
  val log = Logger.getLogger(getClass.getName)

  case class CustomScalarResource(
      value: Double = 0) {
    def toProto(): Protos.CustomResourceDefinition.CustomScalar = {
      val builder = Protos.CustomResourceDefinition.CustomScalar.newBuilder
        .setValue(value)

      builder.build
    }
  }

  case class CustomSetResource(
      value: Set[String] = Set.empty,
      numberRequired: Int = 0) {
    def toProto(): Protos.CustomResourceDefinition.CustomSet = {
      val builder = Protos.CustomResourceDefinition.CustomSet.newBuilder
        .setValue(value)
        .setNumberRequired(numberRequired)

      builder.build
    }
  }

  case class CustomRange(
      numberRequired: Long = 0,
      begin: Long = 0,
      end: Long = Long.MaxValue) {
    def toProto(): Protos.CustomResourceDefinition.CustomRanges.CustomRange = {
      val builder = Protos.CustomResourceDefinition.CustomRanges.CustomRange.newBuilder
        .setNumberRequired(numberRequired)
        .setBegin(begin)
        .setEnd(end)

      builder.build
    }
  }

  case class CustomRangeResource(
      value: Seq[CustomRange]) {
    def toProto(): Protos.CustomResourceDefinition.CustomRanges = {
      val builder = Protos.CustomResourceDefinition.CustomRanges.newBuilder

      value.foreach { r => builder.setValue(r.toProto) }

      builder.build
    }
  }

  def create(resource: Protos.CustomResourceDefinition): Option[CustomResource] = {
    if (resource.hasScalar) {
      Some(CustomResource(scalar =
        Some(CustomScalarResource(resource.getScalar.getValue: Double))))
    }
    else if (resource.hasRange) {
      Some(CustomResource(ranges =
        Some(CustomRangeResource(
          resource.getRange.getValueList.asScala.toSeq.map { range =>
            CustomRange(range.getNumberRequired, begin = range.getBegin, end = range.getEnd)
          }))))
    }
    else if (resource.hasSet) {
      Some(CustomResource(set = Some(CustomSetResource(
        resource.getSet.getValueList.asScala.toSet: Set[String],
        resource.getSet.getNumberRequired))))
    }
    else {
      log.info("TODOC proto resource doesn't have any one of scalar, set, ranges")
      None
    }
    /*
    resource.getType match {
      case Value.Type.SCALAR =>
        Some(CustomResource(scalar = Some(CustomScalarResource(resource.getScalar.getValue: Double))))
      case Value.Type.RANGES =>
        Some(CustomResource(
          ranges = Some(CustomRangeResource(
            resource.getRanges.getRangeList.asScala.toSeq.map { range =>
            CustomRange(0, begin = range.getBegin, end = range.getEnd)
          }))))
      case Value.Type.SET =>
        Some(CustomResource(set =
          Some(CustomSetResource(resource.getSet.getItemList.asScala.toSet: Set[String]))))
      case default =>
        None
    }*/
  }
}
