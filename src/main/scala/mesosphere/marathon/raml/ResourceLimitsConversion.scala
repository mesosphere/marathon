package mesosphere.marathon
package raml

trait ResourceLimitsConversion {

  import ResourceLimitsConversion.{resourceLimitFromDouble, resourceLimitToDouble}
  implicit val ramlResourceLimitsRead = Reads[ResourceLimits, state.ResourceLimits] { resourceLimits =>
    state.ResourceLimits(
      cpus = resourceLimits.cpus.map(resourceLimitToDouble),
      mem = resourceLimits.mem.map(resourceLimitToDouble)
    )
  }

  implicit val ramlResourceLimitsWrite = Writes[state.ResourceLimits, ResourceLimits] { resourceLimits =>
    ResourceLimits(
      cpus = resourceLimits.cpus.map(resourceLimitFromDouble),
      mem = resourceLimits.mem.map(resourceLimitFromDouble)
    )
  }

  implicit val resourceLimitsProtoRamlWrites = Writes[Protos.ResourceLimits, ResourceLimits] { proto =>
    ResourceLimits(
      cpus = if (proto.hasCpus) Some(resourceLimitFromDouble(proto.getCpus)) else None,
      mem = if (proto.hasMem) Some(resourceLimitFromDouble(proto.getMem)) else None
    )
  }
}

object ResourceLimitsConversion {
  def resourceLimitToDouble(resourceLimit: ResourceLimit): Double =
    resourceLimit match {
      case ResourceLimitUnlimited("unlimited") =>
        // This value is understood by protobuf as infinity, and Mesos consequently also understands it
        Double.PositiveInfinity
      case ResourceLimitUnlimited(text) =>
        throw new IllegalStateException(
          s"ResourceLimitUnlimited(${text}) encountered, should be ResourceLimitUnlimited(unlimited)"
        ) // we should never get here
      case ResourceLimitNumber(value) =>
        value
    }

  def resourceLimitFromDouble(limit: Double): ResourceLimit =
    if (limit == Double.PositiveInfinity)
      ResourceLimitUnlimited("unlimited")
    else
      ResourceLimitNumber(limit)
}
