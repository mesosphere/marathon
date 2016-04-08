package mesosphere.marathon.tasks

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.ResourceRole
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
    val result = PortWithRole.createPortsResources(Seq(PortWithRole(ResourceRole.Unreserved, 2)))
    assert(result == Seq(rangesResource(Seq(Range(2, 2)), role = ResourceRole.Unreserved)))
    val result2 = PortWithRole.createPortsResources(Seq(PortWithRole("marathon", 3)))
    assert(result2 == Seq(rangesResource(Seq(Range(3, 3)), role = "marathon")))
  }

  test("one ranges resource for multiple ports of the same role") {
    val result = PortWithRole.createPortsResources(Seq(PortWithRole(ResourceRole.Unreserved, 2), PortWithRole(ResourceRole.Unreserved, 10)))
    assert(result == Seq(rangesResource(Seq(Range(2, 2), Range(10, 10)), role = ResourceRole.Unreserved)))
  }

  test("one ranges resource for consecutive multiple ports of the same role") {
    val result = PortWithRole.createPortsResources(Seq(
      PortWithRole(ResourceRole.Unreserved, 2), PortWithRole(ResourceRole.Unreserved, 10),
      PortWithRole("marathon", 11),
      PortWithRole(ResourceRole.Unreserved, 12)
    ))
    assert(result == Seq(
      rangesResource(Seq(Range(2, 2), Range(10, 10)), role = ResourceRole.Unreserved),
      rangesResource(Seq(Range(11, 11)), role = "marathon"),
      rangesResource(Seq(Range(12, 12)), role = ResourceRole.Unreserved)
    ))
  }

  test("combined consecutive ports of same role into one range") {
    val result = PortWithRole.createPortsResources(Seq(
      PortWithRole(ResourceRole.Unreserved, 2), PortWithRole(ResourceRole.Unreserved, 3)
    ))
    assert(result == Seq(
      rangesResource(Seq(Range(2, 3)), role = ResourceRole.Unreserved)
    ))
  }

  test("complex example") {
    val result = PortWithRole.createPortsResources(Seq(
      PortWithRole(ResourceRole.Unreserved, 2), PortWithRole(ResourceRole.Unreserved, 3), PortWithRole(ResourceRole.Unreserved, 10),
      PortWithRole("marathon", 11),
      PortWithRole(ResourceRole.Unreserved, 12)
    ))
    assert(result == Seq(
      rangesResource(Seq(Range(2, 3), Range(10, 10)), role = ResourceRole.Unreserved),
      rangesResource(Seq(Range(11, 11)), role = "marathon"),
      rangesResource(Seq(Range(12, 12)), role = ResourceRole.Unreserved)
    ))
  }

  def rangesResource(ranges: Seq[Range], role: String): MesosProtos.Resource = {
    import mesosphere.mesos.protos.Implicits._
    RangesResource(Resource.PORTS, ranges, role = role)
  }
}
