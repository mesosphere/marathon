package mesosphere.mesos.protos

case class ScalarResource(
  name: String,
  value: Double,
  role: String = "*") extends Resource

object ScalarResource {
  def cpus(value: Double, role: String = "*"): ScalarResource = ScalarResource(Resource.CPUS, value, role)
  def memory(value: Double, role: String = "*"): ScalarResource = ScalarResource(Resource.MEM, value, role)
  def disk(value: Double, role: String = "*"): ScalarResource = ScalarResource(Resource.DISK, value, role)
  def gpus(value: Double, role: String = "*"): ScalarResource = ScalarResource(Resource.GPUS, value, role)
}
