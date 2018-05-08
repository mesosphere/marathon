package mesosphere.marathon
package tasks

import mesosphere.UnitTest
import mesosphere.marathon.state.ResourceRole
import mesosphere.marathon.tasks.PortsMatcher.PortWithRole
import mesosphere.mesos.protos.{Range, RangesResource, Resource}
import org.apache.mesos.{Protos => MesosProtos}

import scala.collection.immutable.Seq

class PortWithRoleCreatePortsResourcesTest extends UnitTest {

  "PortWithRoleCreatePortsResource" should {
    "create no ranges resource for empty port seq" in {
      val result = PortWithRole.createPortsResources(Seq.empty[PortWithRole])
      assert(result.isEmpty)
    }

    "create ranges resource for single port preserving role" in {
      val result = PortWithRole.createPortsResources(Seq(PortWithRole(ResourceRole.Unreserved, 2)))
      assert(result == Seq(rangesResource(Seq(Range(2, 2)), role = ResourceRole.Unreserved)))
      val result2 = PortWithRole.createPortsResources(Seq(PortWithRole("marathon", 3)))
      assert(result2 == Seq(rangesResource(Seq(Range(3, 3)), role = "marathon")))
    }

    "one ranges resource for multiple ports of the same role" in {
      val result = PortWithRole.createPortsResources(Seq(PortWithRole(ResourceRole.Unreserved, 2), PortWithRole(ResourceRole.Unreserved, 10)))
      assert(result == Seq(rangesResource(Seq(Range(2, 2), Range(10, 10)), role = ResourceRole.Unreserved)))
    }

    "one ranges resource for consecutive multiple ports of the same role" in {
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

    "combined consecutive ports of same role into one range" in {
      val result = PortWithRole.createPortsResources(Seq(
        PortWithRole(ResourceRole.Unreserved, 2), PortWithRole(ResourceRole.Unreserved, 3)
      ))
      assert(result == Seq(
        rangesResource(Seq(Range(2, 3)), role = ResourceRole.Unreserved)
      ))
    }

    "complex example" in {
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
  }

  def rangesResource(ranges: Seq[Range], role: String): MesosProtos.Resource = {
    import mesosphere.mesos.protos.Implicits._
    RangesResource(Resource.PORTS, ranges, role = role)
  }
}
