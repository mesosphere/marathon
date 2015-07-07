package mesosphere.marathon.state

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.apache.mesos.Protos.{ Value }
import mesosphere.marathon.Protos
import mesosphere.mesos.protos.Range
import scala.collection.JavaConverters._

@JsonIgnoreProperties(ignoreUnknown = true)
case class CustomResource(
    scalar: Option[CustomResource.CustomScalar] = None,

    ranges: Option[CustomResource.CustomRanges] = None,

    set: Option[CustomResource.CustomSet] = None) {

  var resourceType: Value.Type = Value.Type.SCALAR
  if (!ranges.isEmpty)
    resourceType = Value.Type.RANGES
  else if (!set.isEmpty)
    resourceType = Value.Type.SET

  def toProto: Protos.CustomResource = {
    val builder = Protos.CustomResource.newBuilder

    resourceType match {
      case Value.Type.SCALAR => builder.setScalar(scalar.get.toProto)
      case Value.Type.RANGES => builder.setRange(ranges.get.toProto)
      case Value.Type.SET    => builder.setSet(set.get.toProto)
      case _                 => ;
    }
    builder.build
  }
}

object CustomResource {

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
      numberRequired: Int = 0,
      begin: Option[Int],
      end: Option[Int]) {
    def toProto(): Protos.CustomResource.CustomRanges.CustomRange = {
      val builder = Protos.CustomResource.CustomRanges.CustomRange.newBuilder
        .setNumberRequired(numberRequired)

      if (!begin.isEmpty && !end.isEmpty) {
        builder.setBegin(begin.get)
        builder.setEnd(end.get)
      }

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
      Some(CustomResource(scalar = Some(CustomScalar(resource.getScalar.getValue: Double))))
    }
    else if (resource.hasRange) {
      Some(CustomResource(ranges = Some(CustomRanges(
        resource.getRange.getValueList.asScala.toSeq.map {
          r: Protos.CustomResource.CustomRanges.CustomRange =>
            CustomRange(r.getNumberRequired, begin = Some(r.getBegin), end = Some(r.getEnd))
        }))))
    }
    else if (resource.hasSet) {
      Some(CustomResource(set = Some(CustomSet(
        resource.getSet.getValueList.asScala.toSet: Set[String],
        resource.getSet.getNumberRequired))))
    }
    else {
      None
    }
  }
}
