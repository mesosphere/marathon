package mesosphere.mesos

import mesosphere.UnitTestLike
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state._
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos._
import org.apache.mesos.{ Protos => MesosProtos }
import org.scalatest.{ AppendedClues, Suites }

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

