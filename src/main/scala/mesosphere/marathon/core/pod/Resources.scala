package mesosphere.marathon.core.pod

case class Resources(cpus: Double, mem: Double, disk: Option[Double], gpus: Option[Int])
