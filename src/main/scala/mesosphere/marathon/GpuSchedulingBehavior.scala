package mesosphere.marathon

sealed trait GpuSchedulingBehavior {
  def name: String
}
object GpuSchedulingBehavior {
  case object Undefined extends GpuSchedulingBehavior {
    override def name = "undefined"
  }

  case object Restricted extends GpuSchedulingBehavior {
    override def name = "restricted"
  }
  case object Unrestricted extends GpuSchedulingBehavior {
    override def name = "unrestricted"
  }

  def all = Seq(Undefined, Restricted, Unrestricted)
}
