package mesosphere.mesos

import com.google.protobuf.TextFormat
import mesosphere.UnitTestLike
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.{ AppDefinition, _ } //, Container, PathId, Timestamp, _ }
import mesosphere.marathon.{ MarathonTestHelper } //MarathonSpec, MarathonTestHelper, Protos }
import mesosphere.mesos.protos.{ Resource, _ }
import org.apache.mesos.{ Protos => MesosProtos }
import org.scalatest.{ AppendedClues, Suites } //, GivenWhenThen, Matchers, Suites }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class TaskBuilderAllTest extends Suites(
  new TaskBuilderConstraintTestSuite,
  new TaskBuilderWithArgsTestSuite,
  new TaskBuilderRolesTestSuite,
  new TaskBuilderIPAddressTestSuite,
  new TaskBuilderPortsTestSuite,
  new TaskBuilderDockerContainerTestSuite,
  new TaskBuilderMesosContainerTestSuite,
  new TaskBuilderEnvironmentTestSuite
)

trait TaskBuilderSuiteBase extends UnitTestLike
    with AppendedClues {

  import mesosphere.mesos.protos.Implicits._

  def buildIfMatches(
    offer: Offer,
    app: AppDefinition,
    mesosRole: Option[String] = None,
    acceptedResourceRoles: Option[Set[String]] = None,
    envVarsPrefix: Option[String] = None) = {
    val builder = new TaskBuilder(
      app,
      s => Task.Id(s.toString),
      MarathonTestHelper.defaultConfig(
        mesosRole = mesosRole,
        acceptedResourceRoles = acceptedResourceRoles,
        envVarsPrefix = envVarsPrefix))

    builder.buildIfMatches(offer, Iterable.empty)
  }

  def assertTaskInfo(taskInfo: MesosProtos.TaskInfo, taskPorts: Seq[Option[Int]], offer: Offer): Unit = {
    val portsFromTaskInfo = {
      val asScalaRanges = for {
        resource <- taskInfo.getResourcesList.asScala if resource.getName == Resource.PORTS
        range <- resource.getRanges.getRangeList.asScala
      } yield range.getBegin to range.getEnd
      asScalaRanges.flatMap(_.iterator).toSet
    }
    assert(portsFromTaskInfo == taskPorts.flatten.toSet)

    // The taskName is the elements of the path, reversed, and joined by dots
    assert("frontend.product" == taskInfo.getName)

    assert(!taskInfo.hasExecutor)
    assert(taskInfo.hasCommand)
    val cmd = taskInfo.getCommand
    assert(cmd.getShell)
    assert(cmd.hasValue)
    assert(cmd.getArgumentsList.asScala.isEmpty)
    assert(cmd.getValue == "foo")

    assert(cmd.hasEnvironment)
    val envVars = cmd.getEnvironment.getVariablesList.asScala
    assert(envVars.exists(v => v.getName == "HOST" && v.getValue == offer.getHostname))
    assert(envVars.exists(v => v.getName == "PORT0" && v.getValue.nonEmpty))
    assert(envVars.exists(v => v.getName == "PORT1" && v.getValue.nonEmpty))
    assert(envVars.exists(v => v.getName == "PORT_8080" && v.getValue.nonEmpty))
    assert(envVars.exists(v => v.getName == "PORT_8081" && v.getValue.nonEmpty))

    val exposesFirstPort =
      envVars.find(v => v.getName == "PORT0").get.getValue == envVars.find(v => v.getName == "PORT_8080").get.getValue
    assert(exposesFirstPort)
    val exposesSecondPort =
      envVars.find(v => v.getName == "PORT1").get.getValue == envVars.find(v => v.getName == "PORT_8081").get.getValue
    assert(exposesSecondPort)

    for (r <- taskInfo.getResourcesList.asScala) {
      assert(ResourceRole.Unreserved == r.getRole)
    }

    assert(taskInfo.hasDiscovery)
    val discoveryInfo = taskInfo.getDiscovery
    val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
      .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
      .setName(taskInfo.getName)
      .setPorts(Helpers.mesosPorts(
        Helpers.mesosPort("", "tcp", Map.empty, taskPorts(0)),
        Helpers.mesosPort("", "tcp", Map.empty, taskPorts(1))
      )).build

    TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
    discoveryInfo should equal(discoveryInfoProto)

    // TODO test for resources etc.
  }

  object Helpers {
    def hostPorts(p: Int*): Seq[Option[Int]] = collection.immutable.Seq(p: _*).map(Some(_))

    def mesosPort(name: String = "", protocol: String = "", labels: Map[String, String] = Map.empty, p: Option[Int]): Option[MesosProtos.Port] =
      p.map { hostPort =>
        val b = MesosProtos.Port.newBuilder.setNumber(hostPort)
        if (name != "") b.setName(name)
        if (protocol != "") b.setProtocol(protocol)
        if (labels.nonEmpty) {
          val labelsBuilder = MesosProtos.Labels.newBuilder()
          labels.foreach {
            case (key, value) =>
              labelsBuilder.addLabels(MesosProtos.Label.newBuilder().setKey(key).setValue(value))
          }
          b.setLabels(labelsBuilder)
        }
        b.build
      }

    def mesosPorts(p: Option[MesosProtos.Port]*) =
      p.flatten.fold(MesosProtos.Ports.newBuilder){
        case (b: MesosProtos.Ports.Builder, p: MesosProtos.Port) =>
          b.addPorts(p)
      }.asInstanceOf[MesosProtos.Ports.Builder]
  }

}

