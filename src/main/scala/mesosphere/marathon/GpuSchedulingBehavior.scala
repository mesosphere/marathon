package mesosphere.marathon

sealed trait GpuSchedulingBehavior {
  def name: String
}
object GpuSchedulingBehavior {
  case object Restricted extends GpuSchedulingBehavior {
    override def name = "restricted"
  }
  case object Unrestricted extends GpuSchedulingBehavior {
    override def name = "unrestricted"
  }

  def all = Seq(Restricted, Unrestricted)
}
