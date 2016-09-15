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
  //new TaskBuilderPortsTestSuite,
  new TaskBuilderDockerContainerTestSuite,
  new TaskBuilderMesosContainerTestSuite
//new TaskBuilderEnvironmentTestSuite
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
//  test("PortDefinition to mesos proto with tcp, udp protocol") {
//
//    Given("a port definition")
//    val portDefinition = PortDefinition(port = 80, protocol = "tcp,udp")
//
//    When("the definition is serialized for mesos")
//    val mesosPortDefinition = PortDefinitionSerializer.toMesosProto(portDefinition)
//
//    Then("the mesos port definition has two ports")
//    mesosPortDefinition.size should be(2)
//    mesosPortDefinition(0).getProtocol should be("tcp")
//    mesosPortDefinition(0).getNumber should be(80)
//
//    mesosPortDefinition(1).getProtocol should be("udp")
//    mesosPortDefinition(1).getNumber should be(80)
//  }
//
//  test("PortDefinition to zk proto with tcp, udp protocol") {
//
//    Given("a port definition")
//    val portDefinition = PortDefinition(port = 80, protocol = "tcp,udp")
//
//    When("the definition is serialized for mesos")
//    val mesosPortDefinition = PortDefinitionSerializer.toProto(portDefinition)
//
//    Then("the zk port definition has one port")
//    mesosPortDefinition.getProtocol should be("tcp,udp")
//    mesosPortDefinition.getNumber should be(80)
//  }
//
//  test("BuildIfMatchesWithLabels") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
//
//    val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(
//      offer,
//      AppDefinition(
//        id = "/product/frontend".toPath,
//        cmd = Some("foo"),
//        cpus = 1.0,
//        mem = 64.0,
//        disk = 1.0,
//        executor = "//cmd",
//        portDefinitions = PortDefinitions(8080, 8081),
//        labels = labels
//      )
//    )
//
//    assert(task.isDefined)
//
//    val (taskInfo, taskPorts) = task.get
//    assertTaskInfo(taskInfo, taskPorts, offer)
//
//    assert(taskInfo.hasLabels)
//    assert(taskInfo.getLabels == expectedLabels)
//  }
//
//  test("BuildIfMatchesWithArgs") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
//
//    val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(
//      offer,
//      AppDefinition(
//        id = "testApp".toPath,
//        args = Some(Seq("a", "b", "c")),
//        cpus = 1.0,
//        mem = 64.0,
//        disk = 1.0,
//        executor = "//cmd",
//        portDefinitions = PortDefinitions(8080, 8081)
//      )
//    )
//
//    assert(task.isDefined)
//
//    val (taskInfo, taskPorts) = task.get
//    val rangeResourceOpt = taskInfo.getResourcesList.asScala.find(r => r.getName == Resource.PORTS)
//    val ranges = rangeResourceOpt.fold(Seq.empty[MesosProtos.Value.Range])(_.getRanges.getRangeList.asScala.to[Seq])
//    val rangePorts = ranges.flatMap(r => r.getBegin to r.getEnd).toSet
//    assert(2 == rangePorts.size)
//    assert(2 == taskPorts.size)
//    assert(taskPorts.flatten.toSet == rangePorts.toSet)
//
//    assert(!taskInfo.hasExecutor)
//    assert(taskInfo.hasCommand)
//    val cmd = taskInfo.getCommand
//    assert(!cmd.getShell)
//    assert(cmd.hasValue)
//    assert(cmd.getArgumentsList.asScala == Seq("a", "b", "c"))
//
//    for (r <- taskInfo.getResourcesList.asScala) {
//      assert(ResourceRole.Unreserved == r.getRole)
//    }
//
//    // TODO test for resources etc.
//  }
//
//  test("BuildIfMatchesWithoutPorts") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
//
//    val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(
//      offer,
//      AppDefinition(
//        id = "/product/frontend".toPath,
//        cmd = Some("foo"),
//        cpus = 1.0,
//        mem = 64.0,
//        disk = 1.0,
//        executor = "//cmd",
//        portDefinitions = Seq.empty
//      )
//    )
//
//    assert(task.isDefined)
//
//    val (taskInfo, taskPorts) = task.get
//    assert(taskPorts.isEmpty)
//
//    val envVariables = taskInfo.getCommand.getEnvironment.getVariablesList.asScala
//    assert(!envVariables.exists(v => v.getName.startsWith("PORT")))
//  }
//
//  def buildIfMatchesWithIpAddress(
//    offer: MesosProtos.Offer,
//    executor: String = AppDefinition.DefaultExecutor,
//    discoveryInfo: DiscoveryInfo = DiscoveryInfo.empty,
//    networkName: Option[String] = None) = buildIfMatches(
//    offer,
//    AppDefinition(
//      id = "testApp".toPath,
//      args = Some(Seq("a", "b", "c")),
//      cpus = 1.0,
//      mem = 64.0,
//      disk = 1.0,
//      portDefinitions = Nil,
//      executor = executor,
//      ipAddress = Some(
//        IpAddress(
//          groups = Seq("a", "b", "c"),
//          labels = Map(
//            "foo" -> "bar",
//            "baz" -> "buzz"
//          ),
//          discoveryInfo = discoveryInfo,
//          networkName = networkName
//        )
//      )
//    )
//  )
//
//  test("BuildIfMatchesWithIpAddress") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
//    val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatchesWithIpAddress(offer)
//    assert(task.isDefined)
//
//    val (taskInfo, taskPorts) = task.get
//
//    taskInfo.hasExecutor should be (false)
//    taskInfo.hasContainer should be (true)
//
//    val networkInfos = taskInfo.getContainer.getNetworkInfosList.asScala
//    networkInfos.size should be (1)
//
//    val networkInfoProto = MesosProtos.NetworkInfo.newBuilder
//      .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance)
//      .addAllGroups(Seq("a", "b", "c").asJava)
//      .setLabels(
//        MesosProtos.Labels.newBuilder.addAllLabels(
//          Seq(
//          MesosProtos.Label.newBuilder.setKey("foo").setValue("bar").build,
//          MesosProtos.Label.newBuilder.setKey("baz").setValue("buzz").build
//        ).asJava
//        ))
//      .build
//    TextFormat.shortDebugString(networkInfos.head) should equal(TextFormat.shortDebugString(networkInfoProto))
//    networkInfos.head should equal(networkInfoProto)
//  }
//
//  test("BuildIfMatchesWithIpAddressAndCustomExecutor") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
//    val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatchesWithIpAddress(offer, executor = "/custom/executor")
//
//    assert(task.isDefined)
//
//    val (taskInfo, taskPorts) = task.get
//
//    taskInfo.hasContainer should be (false)
//    taskInfo.hasExecutor should be (true)
//    taskInfo.getExecutor.hasContainer should be (true)
//
//    val networkInfos = taskInfo.getExecutor.getContainer.getNetworkInfosList.asScala
//    networkInfos.size should be (1)
//
//    val networkInfoProto = MesosProtos.NetworkInfo.newBuilder
//      .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance)
//      .addAllGroups(Seq("a", "b", "c").asJava)
//      .setLabels(
//        MesosProtos.Labels.newBuilder.addAllLabels(
//          Seq(
//          MesosProtos.Label.newBuilder.setKey("foo").setValue("bar").build,
//          MesosProtos.Label.newBuilder.setKey("baz").setValue("buzz").build
//        ).asJava
//        ))
//      .build
//    TextFormat.shortDebugString(networkInfos.head) should equal(TextFormat.shortDebugString(networkInfoProto))
//    networkInfos.head should equal(networkInfoProto)
//
//    taskInfo.hasDiscovery should be (true)
//    taskInfo.getDiscovery.getName should be (taskInfo.getName)
//  }
//
//  test("BuildIfMatchesWithIpAddressAndNetworkName") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
//    val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatchesWithIpAddress(offer, networkName = Some("foonet"))
//
//    assert(task.isDefined)
//
//    val (taskInfo, taskPorts) = task.get
//
//    taskInfo.hasExecutor should be (false)
//    taskInfo.hasContainer should be (true)
//
//    val networkInfos = taskInfo.getContainer.getNetworkInfosList.asScala
//    networkInfos.size should be (1)
//
//    val networkInfoProto = MesosProtos.NetworkInfo.newBuilder
//      .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance)
//      .addAllGroups(Seq("a", "b", "c").asJava)
//      .setLabels(
//        MesosProtos.Labels.newBuilder.addAllLabels(
//          Seq(
//          MesosProtos.Label.newBuilder.setKey("foo").setValue("bar").build,
//          MesosProtos.Label.newBuilder.setKey("baz").setValue("buzz").build
//        ).asJava
//        ))
//      .setName("foonet")
//      .build
//    TextFormat.shortDebugString(networkInfos.head) should equal(TextFormat.shortDebugString(networkInfoProto))
//    networkInfos.head should equal(networkInfoProto)
//  }
//
//  test("BuildIfMatchesWithIpAddressAndDiscoveryInfo") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
//    val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatchesWithIpAddress(
//      offer,
//      discoveryInfo = DiscoveryInfo(
//        ports = Seq(DiscoveryInfo.Port(name = "http", number = 80, protocol = "tcp"))
//      )
//    )
//
//    assert(task.isDefined)
//
//    val (taskInfo, taskPorts) = task.get
//
//    taskInfo.hasExecutor should be (false)
//    taskInfo.hasContainer should be (true)
//
//    val networkInfos = taskInfo.getContainer.getNetworkInfosList.asScala
//    networkInfos.size should be (1)
//
//    val networkInfoProto = MesosProtos.NetworkInfo.newBuilder
//      .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.getDefaultInstance)
//      .addAllGroups(Seq("a", "b", "c").asJava)
//      .setLabels(
//        MesosProtos.Labels.newBuilder.addAllLabels(
//          Seq(
//          MesosProtos.Label.newBuilder.setKey("foo").setValue("bar").build,
//          MesosProtos.Label.newBuilder.setKey("baz").setValue("buzz").build
//        ).asJava
//        ))
//      .build
//    TextFormat.shortDebugString(networkInfos.head) should equal(TextFormat.shortDebugString(networkInfoProto))
//    networkInfos.head should equal(networkInfoProto)
//
//    taskInfo.hasDiscovery should be (true)
//    val discoveryInfo = taskInfo.getDiscovery
//
//    val discoveryInfoProto = MesosProtos.DiscoveryInfo.newBuilder
//      .setVisibility(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK)
//      .setName(taskInfo.getName)
//      .setPorts(
//        MesosProtos.Ports.newBuilder
//        .addPorts(
//          MesosProtos.Port.newBuilder
//          .setName("http")
//          .setNumber(80)
//          .setProtocol("tcp")
//          .build)
//        .build)
//      .build
//    TextFormat.shortDebugString(discoveryInfo) should equal(TextFormat.shortDebugString(discoveryInfoProto))
//    discoveryInfo should equal(discoveryInfoProto)
//  }
//
//  test("BuildIfMatchesWithCommandAndExecutor") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
//      .addResources(ScalarResource("cpus", 1))
//      .addResources(ScalarResource("mem", 128))
//      .addResources(ScalarResource("disk", 2000))
//      .build
//
//    val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
//      offer,
//      AppDefinition(
//        id = "testApp".toPath,
//        cpus = 1.0,
//        mem = 64.0,
//        disk = 1.0,
//        cmd = Some("foo"),
//        executor = "/custom/executor",
//        portDefinitions = PortDefinitions(8080, 8081)
//      )
//    )
//
//    assert(task.isDefined)
//
//    val (taskInfo, _) = task.get
//    assert(taskInfo.hasExecutor)
//    assert(!taskInfo.hasCommand)
//
//    val cmd = taskInfo.getExecutor.getCommand
//    assert(cmd.getShell)
//    assert(cmd.hasValue)
//    assert(cmd.getArgumentsList.asScala.isEmpty)
//    assert(cmd.getValue == "chmod ug+rx '/custom/executor' && exec '/custom/executor' foo")
//  }
//
//  test("BuildIfMatchesWithArgsAndExecutor") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
//
//    val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
//      offer,
//      AppDefinition(
//        id = "testApp".toPath,
//        cpus = 1.0,
//        mem = 64.0,
//        disk = 1.0,
//        args = Some(Seq("a", "b", "c")),
//        executor = "/custom/executor",
//        portDefinitions = PortDefinitions(8080, 8081)
//      )
//    )
//
//    assert(task.isDefined)
//
//    val (taskInfo, _) = task.get
//    val cmd = taskInfo.getExecutor.getCommand
//
//    assert(!taskInfo.hasCommand)
//    assert(cmd.getValue == "chmod ug+rx '/custom/executor' && exec '/custom/executor' a b c")
//  }
//
//  test("BuildIfMatchesWithRole") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 32000, role = "marathon")
//      .addResources(ScalarResource("cpus", 1, ResourceRole.Unreserved))
//      .addResources(ScalarResource("mem", 128, ResourceRole.Unreserved))
//      .addResources(ScalarResource("disk", 1000, ResourceRole.Unreserved))
//      .addResources(ScalarResource("cpus", 2, "marathon"))
//      .addResources(ScalarResource("mem", 256, "marathon"))
//      .addResources(ScalarResource("disk", 2000, "marathon"))
//      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
//      .build
//
//    val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(
//      offer,
//      AppDefinition(
//        id = "testApp".toPath,
//        cpus = 2.0,
//        mem = 200.0,
//        disk = 2.0,
//        executor = "//cmd",
//        portDefinitions = PortDefinitions(8080, 8081)
//      ),
//      mesosRole = Some("marathon"),
//      acceptedResourceRoles = Some(Set("marathon"))
//    )
//
//    assert(task.isDefined)
//
//    val (taskInfo, taskPorts) = task.get
//    val ports = taskInfo.getResourcesList.asScala
//      .find(r => r.getName == Resource.PORTS)
//      .map(r => r.getRanges.getRangeList.asScala.flatMap(range => range.getBegin to range.getEnd))
//      .getOrElse(Seq.empty)
//    assert(ports == taskPorts.flatten)
//
//    for (r <- taskInfo.getResourcesList.asScala) {
//      assert("marathon" == r.getRole)
//    }
//
//    // TODO test for resources etc.
//  }
//
//  test("BuildIfMatchesWithRole2") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOfferWithRole(cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 32000, role = ResourceRole.Unreserved)
//      .addResources(ScalarResource("cpus", 1, ResourceRole.Unreserved))
//      .addResources(ScalarResource("mem", 128, ResourceRole.Unreserved))
//      .addResources(ScalarResource("disk", 1000, ResourceRole.Unreserved))
//      .addResources(ScalarResource("cpus", 2, "marathon"))
//      .addResources(ScalarResource("mem", 256, "marathon"))
//      .addResources(ScalarResource("disk", 2000, "marathon"))
//      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
//      .build
//
//    val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(
//      offer,
//      AppDefinition(
//        id = "testApp".toPath,
//        cpus = 1.0,
//        mem = 64.0,
//        disk = 1.0,
//        executor = "//cmd",
//        portDefinitions = PortDefinitions(8080, 8081)
//      )
//    )
//
//    assert(task.isDefined)
//
//    val (taskInfo, taskPorts) = task.get
//    val ports = taskInfo.getResourcesList.asScala
//      .find(r => r.getName == Resource.PORTS)
//      .map(r => r.getRanges.getRangeList.asScala.flatMap(range => range.getBegin to range.getEnd))
//      .getOrElse(Seq.empty)
//    assert(ports == taskPorts.flatten)
//
//    // In this case, the first roles are sufficient so we'll use those first.
//    for (r <- taskInfo.getResourcesList.asScala) {
//      assert(ResourceRole.Unreserved == r.getRole)
//    }
//
//    // TODO test for resources etc.
//  }
//
//  test("PortMappingsWithZeroContainerPort") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOfferWithRole(
//      cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31000, role = ResourceRole.Unreserved
//    )
//      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
//      .build
//
//    val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
//      offer, AppDefinition(
//      id = "testApp".toPath,
//      cpus = 1.0,
//      mem = 64.0,
//      disk = 1.0,
//      executor = "//cmd",
//      container = Some(Docker(
//        network = Some(DockerInfo.Network.BRIDGE),
//        portMappings = Some(Seq(
//          PortMapping(containerPort = 0, hostPort = Some(0), servicePort = 9000, protocol = "tcp")
//        ))
//      ))
//    )
//    )
//    assert(task.isDefined)
//    val (taskInfo, _) = task.get
//    val hostPort = taskInfo.getContainer.getDocker.getPortMappings(0).getHostPort
//    assert(hostPort == 31000)
//    val containerPort = taskInfo.getContainer.getDocker.getPortMappings(0).getContainerPort
//    assert(containerPort == hostPort)
//  }
//
//  test("PortMappingsWithUserModeAndDefaultPortMapping") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOfferWithRole(
//      cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31010, role = ResourceRole.Unreserved
//    )
//      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
//      .build
//
//    val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
//      offer, AppDefinition(
//      id = "testApp".toPath,
//      cpus = 1.0,
//      mem = 64.0,
//      disk = 1.0,
//      executor = "//cmd",
//      container = Some(Docker(
//        network = Some(DockerInfo.Network.USER),
//        portMappings = Some(Seq(
//          PortMapping()
//        ))
//      )),
//      portDefinitions = Seq.empty,
//      ipAddress = Some(IpAddress(networkName = Some("vnet")))
//    )
//    )
//    assert(task.isDefined, "expected task to match offer")
//    val (taskInfo, _) = task.get
//    assert(taskInfo.getContainer.getDocker.getPortMappingsList.size == 0)
//
//    val envVariables = taskInfo.getCommand.getEnvironment.getVariablesList.asScala
//    assert(envVariables.exists(v => v.getName == "PORT"))
//    assert(envVariables.exists(v => v.getName == "PORT0"))
//    assert(envVariables.exists(v => v.getName == "PORTS"))
//    assert(envVariables.filter(v => v.getName.startsWith("PORT_")).size == 1)
//  }
//
//  test("PortMappingsWithoutHostPort") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOfferWithRole(
//      cpus = 1.0, mem = 128.0, disk = 1000.0, beginPort = 31000, endPort = 31010, role = ResourceRole.Unreserved
//    )
//      .addResources(RangesResource(Resource.PORTS, Seq(protos.Range(33000, 34000)), "marathon"))
//      .build
//
//    val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
//      offer, AppDefinition(
//      id = "testApp".toPath,
//      cpus = 1.0,
//      mem = 64.0,
//      disk = 1.0,
//      executor = "//cmd",
//      container = Some(Docker(
//        network = Some(DockerInfo.Network.USER),
//        portMappings = Some(Seq(
//          PortMapping(containerPort = 0, hostPort = Some(31000), servicePort = 9000, protocol = "tcp"),
//          PortMapping(containerPort = 0, hostPort = None, servicePort = 9001, protocol = "tcp"),
//          PortMapping(containerPort = 0, hostPort = Some(31005), servicePort = 9002, protocol = "tcp")
//        ))
//      ))
//    )
//    )
//    assert(task.isDefined, "expected task to match offer")
//    val (taskInfo, _) = task.get
//    assert(taskInfo.getContainer.getDocker.getPortMappingsList.size == 2, "expected 2 port mappings (ignoring hostPort == None)")
//
//    var hostPort = taskInfo.getContainer.getDocker.getPortMappings(0).getHostPort
//    assert(hostPort == 31000)
//    var containerPort = taskInfo.getContainer.getDocker.getPortMappings(0).getContainerPort
//    assert(containerPort == hostPort)
//
//    hostPort = taskInfo.getContainer.getDocker.getPortMappings(1).getHostPort
//    assert(hostPort == 31005)
//    containerPort = taskInfo.getContainer.getDocker.getPortMappings(1).getContainerPort
//    assert(containerPort == hostPort)
//  }
//
//  test("BuildIfMatchesWithRackIdConstraint") {
//
//    Given("an offer")
//    val offer = MarathonTestHelper.makeBasicOffer(1.0, 128.0, 31000, 32000)
//      .addAttributes(TextAttribute("rackid", "1"))
//      .build
//
//    val app = MarathonTestHelper.makeBasicApp().copy(
//      constraints = Set(
//        Protos.Constraint.newBuilder
//          .setField("rackid")
//          .setOperator(Protos.Constraint.Operator.UNIQUE)
//          .build()
//      )
//    )
//
//    val t1 = makeSampleTask(app.id, "rackid", "2")
//    val t2 = makeSampleTask(app.id, "rackid", "3")
//    val s = Set(t1, t2)
//
//    val builder = new TaskBuilder(
//      app,
//      s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())
//
//    val task = builder.buildIfMatches(offer, s)
//
//    assert(task.isDefined)
//    // TODO test for resources etc.
//  }
//
//  test("RackAndHostConstraints") {
//    // Test the case where we want tasks to be balanced across racks/AZs
//    // and run only one per machine
//    val app = MarathonTestHelper.makeBasicApp().copy(
//      instances = 10,
//      versionInfo = OnlyVersion(Timestamp(10)),
//      constraints = Set(
//        Protos.Constraint.newBuilder.setField("rackid").setOperator(Protos.Constraint.Operator.GROUP_BY).setValue("3").build,
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
//    val offerRack1HostA = MarathonTestHelper.makeBasicOffer()
//      .setHostname("alpha")
//      .addAttributes(TextAttribute("rackid", "1"))
//      .build
//    shouldBuildTask("Should take first offer", offerRack1HostA)
//
//    val offerRack1HostB = MarathonTestHelper.makeBasicOffer()
//      .setHostname("beta")
//      .addAttributes(TextAttribute("rackid", "1"))
//      .build
//    shouldNotBuildTask("Should not take offer for the same rack", offerRack1HostB)
//
//    val offerRack2HostC = MarathonTestHelper.makeBasicOffer()
//      .setHostname("gamma")
//      .addAttributes(TextAttribute("rackid", "2"))
//      .build
//    shouldBuildTask("Should take offer for different rack", offerRack2HostC)
//
//    // Nothing prevents having two hosts with the same name in different racks
//    val offerRack3HostA = MarathonTestHelper.makeBasicOffer()
//      .setHostname("alpha")
//      .addAttributes(TextAttribute("rackid", "3"))
//      .build
//    shouldNotBuildTask("Should not take offer in different rack with non-unique hostname", offerRack3HostA)
//  }
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
//  test("TaskWillCopyFetchIntoCommand") {
//    val command = TaskBuilder.commandInfo(
//      runSpec = AppDefinition(
//        fetch = Seq(
//          FetchUri(uri = "http://www.example.com", extract = false, cache = true, executable = false),
//          FetchUri(uri = "http://www.example2.com", extract = true, cache = true, executable = true)
//        )
//      ),
//      taskId = Some(Task.Id("task-123")),
//      host = Some("host.mega.corp"),
//      hostPorts = Helpers.hostPorts(1000, 1001),
//      envPrefix = None
//    )
//
//    assert(command.getUris(0).getValue.contentEquals("http://www.example.com"))
//    assert(command.getUris(0).getCache)
//    assert(!command.getUris(0).getExtract)
//    assert(!command.getUris(0).getExecutable)
//
//    assert(command.getUris(1).getValue.contentEquals("http://www.example2.com"))
//    assert(command.getUris(1).getCache)
//    assert(command.getUris(1).getExtract)
//    assert(command.getUris(1).getExecutable)
//  }
//
//  // #2865 Multiple explicit ports are mixed up in task json
//  test("build with requirePorts preserves the port order") {
//    val offer = MarathonTestHelper.makeBasicOffer(cpus = 2.0, mem = 128.0, disk = 2000.0, beginPort = 25000, endPort = 26000).build
//
//    val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(
//      offer,
//      AppDefinition(
//        id = "/product/frontend".toPath,
//        cmd = Some("foo"),
//        portDefinitions = PortDefinitions(25552, 25551),
//        requirePorts = true
//      )
//    )
//
//    val Some((taskInfo, _)) = task
//
//    val env: Map[String, String] =
//      taskInfo.getCommand.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap
//
//    assert("25552" == env("PORT0"))
//    assert("25552" == env("PORT_25552"))
//    assert("25551" == env("PORT1"))
//    assert("25551" == env("PORT_25551"))
//
//    val portsFromTaskInfo = {
//      val asScalaRanges = for {
//        resource <- taskInfo.getResourcesList.asScala if resource.getName == Resource.PORTS
//        range <- resource.getRanges.getRangeList.asScala
//      } yield range.getBegin to range.getEnd
//      asScalaRanges.flatMap(_.iterator).toList
//    }
//    assert(portsFromTaskInfo == Seq(25552, 25551))
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
//  def buildIfMatches(
//    offer: Offer,
//    app: AppDefinition,
//    mesosRole: Option[String] = None,
//    acceptedResourceRoles: Option[Set[String]] = None,
//    envVarsPrefix: Option[String] = None) = {
//    val builder = new TaskBuilder(
//      app,
//      s => Task.Id(s.toString),
//      MarathonTestHelper.defaultConfig(
//        mesosRole = mesosRole,
//        acceptedResourceRoles = acceptedResourceRoles,
//        envVarsPrefix = envVarsPrefix))
//
//    builder.buildIfMatches(offer, Iterable.empty)
//  }
//
//  def makeSampleTask(id: PathId, attr: String, attrVal: String) = {
//    import MarathonTestHelper.Implicits._
//    MarathonTestHelper
//      .stagedTask(taskId = id.toString)
//      .withAgentInfo(_.copy(attributes = Iterable(TextAttribute(attr, attrVal))))
//      .withHostPorts(Seq(999))
//  }
//
//  private def assertTaskInfo(taskInfo: MesosProtos.TaskInfo, taskPorts: Seq[Option[Int]], offer: Offer): Unit = {
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
