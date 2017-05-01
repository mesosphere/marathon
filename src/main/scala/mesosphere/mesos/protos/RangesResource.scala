package mesosphere.mesos.protos

import scala.collection.immutable.Seq

case class RangesResource(
  name: String,
  ranges: Seq[Range],
  role: String = "*") extends Resource

object RangesResource {
  def ports(ranges: Seq[Range], role: String = "*"): RangesResource =
    RangesResource(Resource.PORTS, ranges, role)
}
