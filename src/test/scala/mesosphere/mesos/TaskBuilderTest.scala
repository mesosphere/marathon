package mesosphere.mesos

import com.google.protobuf.TextFormat
import mesosphere.UnitTestLike
//import mesosphere.marathon.api.serialization.PortDefinitionSerializer
//import mesosphere.marathon.state.AppDefinition.VersionInfo.OnlyVersion
import mesosphere.marathon.core.task.Task
//import mesosphere.marathon.state.Container.Docker
//import mesosphere.marathon.state.Container.Docker.PortMapping
//import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, _ } //, Container, PathId, Timestamp, _ }
import mesosphere.marathon.{ MarathonTestHelper } //MarathonSpec, MarathonTestHelper, Protos }
import mesosphere.mesos.protos.{ Resource, _ }
//import org.apache.mesos.Protos.ContainerInfo.DockerInfo
import org.apache.mesos.{ Protos => MesosProtos }
import org.scalatest.{ AppendedClues, Suites } //, GivenWhenThen, Matchers, Suites }

import scala.collection.JavaConverters._
//import scala.concurrent.duration._
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

//class TaskBuilderTest extends MarathonSpec
//    with AppendedClues
//    with GivenWhenThen
//    with Matchers {
//
//  import mesosphere.mesos.protos.Implicits._
//
//  val labels = Map("foo" -> "bar", "test" -> "test")
//
//  val expectedLabels = MesosProtos.Labels.newBuilder.addAllLabels(
//    labels.map {
//    case (mKey, mValue) =>
//      MesosProtos.Label.newBuilder.setKey(mKey).setValue(mValue).build()
//  }.asJava).build
//
//
//
//
//
//  test("UniqueHostNameAndClusterAttribute") {
//    val app = MarathonTestHelper.makeBasicApp().copy(
//      instances = 10,
//      constraints = Set(
//        Protos.Constraint.newBuilder.setField("spark").setOperator(Protos.Constraint.Operator.CLUSTER).setValue("enabled").build,
//        Protos.Constraint.newBuilder.setField("hostname").setOperator(Protos.Constraint.Operator.UNIQUE).build
//      )
//    )
//
//    var runningTasks = Set.empty[Task]
//
//    val builder = new TaskBuilder(
//      app,
//      s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())
//
//    def shouldBuildTask(message: String, offer: Offer) {
//      val Some((taskInfo, ports)) = builder.buildIfMatches(offer, runningTasks)
//      val marathonTask = MarathonTestHelper.makeTaskFromTaskInfo(taskInfo, offer)
//      runningTasks += marathonTask
//    }
//
//    def shouldNotBuildTask(message: String, offer: Offer) {
//      val tupleOption = builder.buildIfMatches(offer, runningTasks)
//      assert(tupleOption.isEmpty, message)
//    }
//
//    val offerHostA = MarathonTestHelper.makeBasicOffer()
//      .setHostname("alpha")
//      .addAttributes(TextAttribute("spark", "disabled"))
//      .build
//    shouldNotBuildTask("Should not take an offer with spark:disabled", offerHostA)
//
//    val offerHostB = MarathonTestHelper.makeBasicOffer()
//      .setHostname("beta")
//      .addAttributes(TextAttribute("spark", "enabled"))
//      .build
//    shouldBuildTask("Should take offer with spark:enabled", offerHostB)
//  }
//
//  test("build with virtual networking and optional hostports preserves the port order") {
//    val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 25000, endPort = 26003).build
//
//    val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
//      offer,
//      AppDefinition(
//        id = "/product/frontend".toPath,
//        cmd = Some("foo"),
//        container = Some(Docker(
//          image = "jdef/foo",
//          network = Some(MesosProtos.ContainerInfo.DockerInfo.Network.USER),
//          portMappings = Some(Seq(
//            // order is important here since it impacts the specific assertions that follow
//            Container.Docker.PortMapping(containerPort = 0, hostPort = None),
//            Container.Docker.PortMapping(containerPort = 100, hostPort = Some(0)),
//            Container.Docker.PortMapping(containerPort = 200, hostPort = Some(25002)),
//            Container.Docker.PortMapping(containerPort = 0, hostPort = Some(25001)),
//            Container.Docker.PortMapping(containerPort = 400, hostPort = None),
//            Container.Docker.PortMapping(containerPort = 0, hostPort = Some(0))
//          ))
//        )),
//        ipAddress = Some(IpAddress(networkName = Some("vnet"))),
//        portDefinitions = Nil
//      )
//    )
//
//    val Some((taskInfo, _)) = task
//
//    val env: Map[String, String] =
//      taskInfo.getCommand.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap
//
//    // port0 is not allocated from the offer since it's container-only, but it should also not
//    // overlap with other (fixed or dynamic) container ports
//    assert(env.contains("PORT0"))
//    val p0 = env("PORT0")
//    assert("0" != env("PORT0"))
//    assert("25003" != env("PORT0"))
//    assert("25002" != env("PORT0"))
//    assert("25001" != env("PORT0"))
//    assert("25000" != env("PORT0"))
//    assert("100" != env("PORT0"))
//    assert("200" != env("PORT0"))
//    assert("400" != env("PORT0"))
//    assert(p0 == env("PORT_" + p0))
//    //? how to test there's never any overlap?
//
//    // port1 picks up a dynamic host port allocated from the offer
//    assert(env.contains("PORT1"))
//    assert("25002" != env("PORT1"))
//    assert("25001" != env("PORT1"))
//    assert("0" != env("PORT1"))
//    assert(env("PORT1") == env("PORT_100"))
//
//    // port2 picks up a fixed host port allocated from the offer
//    assert("25002" == env("PORT2"))
//    assert("25002" == env("PORT_200"))
//
//    // port3 picks up a fixed host port allocated from the offer
//    assert("25001" == env("PORT3"))
//    assert("25001" == env("PORT_25001"))
//
//    // port4 is not allocated from the offer, but it does specify a fixed container port
//    assert("400" == env("PORT4"))
//    assert("400" == env("PORT_400"))
//
//    // port5 is dynamic, allocated from offer, and should be inherited by the container port
//    assert(env.contains("PORT5"))
//    val p5 = env("PORT5")
//    assert(p5 == env("PORT_" + p5))
//
//    val portsFromTaskInfo = {
//      val asScalaRanges = for {
//        resource <- taskInfo.getResourcesList.asScala if resource.getName == Resource.PORTS
//        range <- resource.getRanges.getRangeList.asScala
//      } yield range.getBegin to range.getEnd
//      asScalaRanges.flatMap(_.iterator).toList
//    }
//    assert(4 == portsFromTaskInfo.size)
//    assert(portsFromTaskInfo.exists(_ == 25002))
//    assert(portsFromTaskInfo.exists(_ == 25001))
//    assert(portsFromTaskInfo.exists(_.toString == env("PORT1")))
//    assert(portsFromTaskInfo.exists(_.toString == env("PORT5")))
//  }
//
//  test("taskKillGracePeriod specified in app definition is passed through to TaskInfo") {
//    val seconds = 12345.seconds
//    val app = MarathonTestHelper.makeBasicApp().copy(
//      taskKillGracePeriod = Some(seconds)
//    )
//
//    val offer = MarathonTestHelper.makeBasicOffer(1.0, 128.0, 31000, 32000).build
//    val builder = new TaskBuilder(app, s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())
//    val runningTasks = Set.empty[Task]
//    val task = builder.buildIfMatches(offer, runningTasks)
//
//    assert(task.isDefined)
//    val (taskInfo, taskPorts) = task.get
//    assert(taskInfo.hasKillPolicy)
//    val killPolicy = taskInfo.getKillPolicy
//    assert(killPolicy.hasGracePeriod)
//    val gracePeriod = killPolicy.getGracePeriod
//    assert(gracePeriod.hasNanoseconds)
//    val nanoSeconds = gracePeriod.getNanoseconds
//    assert(nanoSeconds == seconds.toNanos)
//  }
//
//
//    val portsFromTaskInfo = {
//      val asScalaRanges = for {
//        resource <- taskInfo.getResourcesList.asScala if resource.getName == Resource.PORTS
//        range <- resource.getRanges.getRangeList.asScala
//      } yield range.getBegin to range.getEnd
//      asScalaRanges.flatMap(_.iterator).toSet
//    }
//    assert(portsFromTaskInfo == taskPorts.flatten.toSet)
//
//    // The taskName is the elements of the path, reversed, and joined by dots
//    assert("frontend.product" == taskInfo.getName)
//
//    assert(!taskInfo.hasExecutor)
//    assert(taskInfo.hasCommand)
//    val cmd = taskInfo.getCommand
//    assert(cmd.getShell)
//    assert(cmd.hasValue)
//    assert(cmd.getArgumentsList.asScala.isEmpty)
//    assert(cmd.getValue == "foo")
//
//    assert(cmd.hasEnvironment)
//    val envVars = cmd.getEnvironment.getVariablesList.asScala
//    assert(envVars.exists(v => v.getName == "HOST" && v.getValue == offer.getHostname))
//    assert(envVars.exists(v => v.getName == "PORT0" && v.getValue.nonEmpty))
//    assert(envVars.exists(v => v.getName == "PORT1" && v.getValue.nonEmpty))
//    assert(envVars.exists(v => v.getName == "PORT_8080" && v.getValue.nonEmpty))
//    assert(envVars.exists(v => v.getName == "PORT_8081" && v.getValue.nonEmpty))
//
//    val exposesFirstPort =
//      envVars.find(v => v.getName == "PORT0").get.getValue == envVars.find(v => v.getName == "PORT_8080").get.getValue
//    assert(exposesFirstPort)
//    val exposesSecondPort =
//      envVars.find(v => v.getName == "PORT1").get.getValue == envVars.find(v => v.getName == "PORT_8081").get.getValue
//    assert(exposesSecondPort)
//
//    for (r <- taskInfo.getResourcesList.asScala) {
//      assert(ResourceRole.Unreserved == r.getRole)
//    }
//
//    assert(taskInfo.hasDiscovery)
//    val discoveryInfo = taskInfo.getDiscovery
//    val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
//      .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
//      .setName(taskInfo.getName)
//      .setPorts(Helpers.mesosPorts(
//        Helpers.mesosPort("", "tcp", Map.empty, taskPorts(0)),
//        Helpers.mesosPort("", "tcp", Map.empty, taskPorts(1))
//      )).build
//
//    TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
//    discoveryInfo should equal(discoveryInfoProto)
//
//    // TODO test for resources etc.
//  }
//
//  object Helpers {
//    def hostPorts(p: Int*): Seq[Option[Int]] = collection.immutable.Seq(p: _*).map(Some(_))
//
//    def mesosPort(name: String = "", protocol: String = "", labels: Map[String, String] = Map.empty, p: Option[Int]): Option[MesosProtos.Port] =
//      p.map { hostPort =>
//        val b = MesosProtos.Port.newBuilder.setNumber(hostPort)
//        if (name != "") b.setName(name)
//        if (protocol != "") b.setProtocol(protocol)
//        if (labels.nonEmpty) {
//          val labelsBuilder = MesosProtos.Labels.newBuilder()
//          labels.foreach {
//            case (key, value) =>
//              labelsBuilder.addLabels(MesosProtos.Label.newBuilder().setKey(key).setValue(value))
//          }
//          b.setLabels(labelsBuilder)
//        }
//        b.build
//      }
//
//    def mesosPorts(p: Option[MesosProtos.Port]*) =
//      p.flatten.fold(MesosProtos.Ports.newBuilder){
//        case (b: MesosProtos.Ports.Builder, p: MesosProtos.Port) =>
//          b.addPorts(p)
//      }.asInstanceOf[MesosProtos.Ports.Builder]
//  }
//}
