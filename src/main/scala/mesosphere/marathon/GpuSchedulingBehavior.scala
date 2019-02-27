package mesosphere.marathon

sealed trait GpuSchedulingBehavior {
  def name: String
}
object GpuSchedulingBehavior {
  case object Undefined extends GpuSchedulingBehavior {
    override def name = "undefined"
    if (BuildInfo.version < SemVer(1, 9, 0))
      "undefined"
    else {
      /* See MARATHON-8589 */
      throw new NotImplementedError("Hi programmer! Please delete the definition Undefined and fix all resulting compile issues.")
    }
  }

  case object Restricted extends GpuSchedulingBehavior {
    override def name = "restricted"
  }
  case object Unrestricted extends GpuSchedulingBehavior {
    override def name = "unrestricted"
  }

  def all = Seq(Undefined, Restricted, Unrestricted)
}
