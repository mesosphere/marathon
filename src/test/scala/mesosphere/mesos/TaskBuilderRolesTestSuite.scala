package mesosphere.mesos

import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.mesos.protos._
import org.apache.mesos.{ Protos => MesosProtos }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilderRolesTestSuite extends TaskBuilderSuiteBase {

  import mesosphere.mesos.protos.Implicits._

  "TaskBuilder" when {

    "given an offer with resource roles and an app definition" should {
      val offer = MarathonTestHelper.makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 32000, role = "marathon")
        .addResources(ScalarResource("cpus", 1, ResourceRole.Unreserved))
        .addResources(ScalarResource("mem", 128, ResourceRole.Unreserved))
        .addResources(ScalarResource("disk", 1000, ResourceRole.Unreserved))
        .addResources(ScalarResource("cpus", 2, "marathon"))
        .addResources(ScalarResource("mem", 256, "marathon"))
        .addResources(ScalarResource("disk", 2000, "marathon"))
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          cpus = 2.0,
          mem = 200.0,
          disk = 2.0,
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] =
        buildIfMatches(offer, appDef, mesosRole = Some("marathon"), acceptedResourceRoles = Some(Set("marathon")))
      val (taskInfo: MesosProtos.TaskInfo, taskPorts: Seq[Option[Int]]) = task.get
      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

      "return a defined task" in { task should be('defined) }

      "define the same ports in resources as in task" in {
        val ports = taskInfo.getResourcesList.asScala
          .find(r => r.getName == Resource.PORTS)
          .map(r => r.getRanges.getRangeList.asScala.flatMap(range => range.getBegin to range.getEnd))
          .getOrElse(Seq.empty)
        ports should be(taskPorts.flatten)
      }

      "set the resource roles to marathon" in {
        for (r <- taskInfo.getResourcesList.asScala) {
          r.getRole should be("marathon")
        }
      }
      // TODO(karsten): Do these numbers make sense?
      "set an appropriate cpu share" in { resource("cpus") should be(ScalarResource("cpus", 1, "marathon")) }
      "set an appropriate mem share" in { resource("mem") should be(ScalarResource("mem", 72, "marathon")) }
      "set an appropriate disk share" in { resource("disk") should be(ScalarResource("disk", 2.0, "marathon")) }
    }

    "given an offer with resource roles and an app definition and no predefined role" should {

      val offer = MarathonTestHelper.makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 32000, role = ResourceRole.Unreserved)
        .addResources(ScalarResource("cpus", 1, ResourceRole.Unreserved))
        .addResources(ScalarResource("mem", 128, ResourceRole.Unreserved))
        .addResources(ScalarResource("disk", 1000, ResourceRole.Unreserved))
        .addResources(ScalarResource("cpus", 2, "marathon"))
        .addResources(ScalarResource("mem", 256, "marathon"))
        .addResources(ScalarResource("disk", 2000, "marathon"))
        .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
        .build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo: MesosProtos.TaskInfo, taskPorts) = task.get
      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

      "return a defined task" in { task should be('defined) }

      "define the same ports in resources as in task" in {
        val ports = taskInfo.getResourcesList.asScala
          .find(r => r.getName == Resource.PORTS)
          .map(r => r.getRanges.getRangeList.asScala.flatMap(range => range.getBegin to range.getEnd))
          .getOrElse(Seq.empty)
        ports should be(taskPorts.flatten)
      }

      // In this case, the first roles are sufficient so we'll use those first.
      "set the resource roles to unreserved" in {
        for (r <- taskInfo.getResourcesList.asScala) {
          r.getRole should be(ResourceRole.Unreserved)
        }
      }

      // TODO(karsten): Do these numbers make sense?
      "set an appropriate cpu share" in { resource("cpus") should be(ScalarResource("cpus", 1)) }
      "set an appropriate mem share" in { resource("mem") should be(ScalarResource("mem", 64)) }
      "set an appropriate disk share" in { resource("disk") should be(ScalarResource("disk", 1.0)) }
    }
  }
}
