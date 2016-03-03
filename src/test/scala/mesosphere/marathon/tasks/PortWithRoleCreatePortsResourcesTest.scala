package mesosphere.marathon.tasks

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.tasks.PortsMatcher.PortWithRole
import mesosphere.mesos.protos.{ Resource, RangesResource, Range }
import scala.collection.immutable.Seq
import org.apache.mesos.{ Protos => MesosProtos }

class PortWithRoleCreatePortsResourcesTest extends MarathonSpec {
  test("create no ranges resource for empty port seq") {
    val result = PortWithRole.createPortsResources(Seq.empty[PortWithRole])
    assert(result.isEmpty)
  }

  test("create ranges resource for single port preserving role") {
    val result = PortWithRole.createPortsResources(Seq(PortWithRole("*", 2)))
    assert(result == Seq(rangesResource(Seq(Range(2, 2)), role = "*")))
    val result2 = PortWithRole.createPortsResources(Seq(PortWithRole("marathon", 3)))
    assert(result2 == Seq(rangesResource(Seq(Range(3, 3)), role = "marathon")))
  }

  test("one ranges resource for multiple ports of the same role") {
    val result = PortWithRole.createPortsResources(Seq(PortWithRole("*", 2), PortWithRole("*", 10)))
    assert(result == Seq(rangesResource(Seq(Range(2, 2), Range(10, 10)), role = "*")))
  }

  test("one ranges resource for consecutive multiple ports of the same role") {
    val result = PortWithRole.createPortsResources(Seq(
      PortWithRole("*", 2), PortWithRole("*", 10),
      PortWithRole("marathon", 11),
      PortWithRole("*", 12)
    ))
    assert(result == Seq(
      rangesResource(Seq(Range(2, 2), Range(10, 10)), role = "*"),
      rangesResource(Seq(Range(11, 11)), role = "marathon"),
      rangesResource(Seq(Range(12, 12)), role = "*")
    ))
  }

  test("combined consecutive ports of same role into one range") {
    val result = PortWithRole.createPortsResources(Seq(
      PortWithRole("*", 2), PortWithRole("*", 3)
    ))
    assert(result == Seq(
      rangesResource(Seq(Range(2, 3)), role = "*")
    ))
  }

  test("complex example") {
    val result = PortWithRole.createPortsResources(Seq(
      PortWithRole("*", 2), PortWithRole("*", 3), PortWithRole("*", 10),
      PortWithRole("marathon", 11),
      PortWithRole("*", 12)
    ))
    assert(result == Seq(
      rangesResource(Seq(Range(2, 3), Range(10, 10)), role = "*"),
      rangesResource(Seq(Range(11, 11)), role = "marathon"),
      rangesResource(Seq(Range(12, 12)), role = "*")
    ))
  }

  def rangesResource(ranges: Seq[Range], role: String): MesosProtos.Resource = {
    import mesosphere.mesos.protos.Implicits._
    RangesResource(Resource.PORTS, ranges, role = role)
  }
}
