package mesosphere.marathon.state

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.apache.mesos.Protos.{ Value }
import mesosphere.marathon.Protos
//import org.apache.log4j.Logger
import mesosphere.mesos.protos.Range
import scala.collection.JavaConverters._

@JsonIgnoreProperties(ignoreUnknown = true)
case class CustomResource(
    name: String,

    scalar: Option[CustomResource.CustomScalar] = None,

    ranges: Option[CustomResource.CustomRanges] = None,

    set: Option[CustomResource.CustomSet] = None) {

  //val log = Logger.getLogger(getClass.getName)

  val resourceSet = Set(scalar, ranges, set)

  if (resourceSet.filter(!_.isEmpty).size == 0) {
    //log.info("No resource value (scalar, range, or set) specified for custom resource") //TODOC change to warn
  }

  if (resourceSet.filter(!_.isEmpty).size > 1) {
    //log.info("Multiple resources types specified for (scalar, range, or set) specified" +
    //  " for custom resource")
  }

  var resourceType: Value.Type = Value.Type.SCALAR
  if (!ranges.isEmpty)
    resourceType = Value.Type.RANGES
  else if (!set.isEmpty)
    resourceType = Value.Type.SET

  def getType: Value.Type = resourceType

  def toProto: Protos.CustomResource = {
    val builder = Protos.CustomResource.newBuilder

    builder.setName(name)
    getType match {
      case Value.Type.SCALAR => builder.setScalar(scalar.get.toProto)
      case Value.Type.RANGES => builder.setRange(ranges.get.toProto)
      case Value.Type.SET    => builder.setSet(set.get.toProto)
      case _                 => ;
    }
    builder.build
  }
}

object CustomResource {
  //val standardResources = Set(Resource.CPUS, Resource.MEM, Resource.DISK, Resource.PORTS)
  //TODOC throw error if standardResource?
  //val log = Logger.getLogger(getClass.getName)

  case class CustomScalar(
      value: Double = 0) {
    def toProto(): Protos.CustomResource.CustomScalar = {
      val builder = Protos.CustomResource.CustomScalar.newBuilder
        .setValue(value)

      builder.build
    }
  }

  case class CustomSet(
      value: Set[String] = Set.empty,
      numberRequired: Int = 0) {
    def toProto(): Protos.CustomResource.CustomSet = {
      val builder = Protos.CustomResource.CustomSet.newBuilder
        .setNumberRequired(numberRequired)

      value.foreach { s => builder.addValue(s) }

      builder.build
    }
  }

  case class CustomRange(
      numberRequired: Long = 0,
      begin: Option[Long] = Some(0),
      end: Option[Long] = Some(Long.MaxValue)) {
    def toProto(): Protos.CustomResource.CustomRanges.CustomRange = {
      val builder = Protos.CustomResource.CustomRanges.CustomRange.newBuilder
        .setNumberRequired(numberRequired)
        .setBegin(begin.get)
        .setEnd(end.get)

      builder.build
    }
  }

  case class CustomRanges(
      value: Seq[CustomRange]) {
    def toProto(): Protos.CustomResource.CustomRanges = {
      val builder = Protos.CustomResource.CustomRanges.newBuilder

      value.foreach { r => builder.addValue(r.toProto) }

      builder.build
    }
  }

  def create(resource: Protos.CustomResource): Option[CustomResource] = {
    if (resource.hasScalar) {
      Some(CustomResource(resource.getName,
        scalar = Some(CustomScalar(resource.getScalar.getValue: Double))))
    }
    else if (resource.hasRange) {
      Some(CustomResource(resource.getName,
        ranges = Some(CustomRanges(
          resource.getRange.getValueList.asScala.toSeq.map {
            r: Protos.CustomResource.CustomRanges.CustomRange =>
              CustomRange(r.getNumberRequired, begin = Some(r.getBegin), end = Some(r.getEnd))
          }))))
    }
    else if (resource.hasSet) {
      Some(CustomResource(resource.getName,
        set = Some(CustomSet(
          resource.getSet.getValueList.asScala.toSet: Set[String],
          resource.getSet.getNumberRequired))))
    }
    else {
      //log.info("TODOC proto resource doesn't have any one of scalar, set, ranges")
      None
    }
  }
}
