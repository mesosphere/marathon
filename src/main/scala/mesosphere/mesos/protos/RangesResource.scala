package mesosphere.mesos.protos

case class RangesResource(
  name: String,
  ranges: Seq[Range],
  role: String = "*") extends Resource
