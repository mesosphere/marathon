package mesosphere.mesos.protos

case class ScalarResource(
  name: String,
  value: Double,
  role: String = "*") extends Resource
