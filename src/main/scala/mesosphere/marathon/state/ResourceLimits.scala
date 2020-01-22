package mesosphere.marathon
package state

case class ResourceLimits(cpus: Option[Double], mem: Option[Double])

object ResourceLimits {
  def resourceLimitsToProto(limits: ResourceLimits): Protos.ResourceLimits = {
    val b = Protos.ResourceLimits.newBuilder()
    limits.cpus.foreach(b.setCpus)
    limits.mem.foreach(b.setMem)
    b.build
  }

  def resourceLimitsFromProto(proto: Protos.ResourceLimits): ResourceLimits = {
    ResourceLimits(
      cpus = if (proto.hasCpus) Some(proto.getCpus) else None,
      mem = if (proto.hasMem) Some(proto.getMem) else None
    )
  }
}
