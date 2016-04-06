package mesosphere.marathon.state

import mesosphere.marathon.MarathonTestHelper.Implicits._
import mesosphere.marathon.Protos.ServiceDefinition
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper, Protos }
import org.apache.mesos.{ Protos => mesos }
import org.scalatest.Matchers

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class AppDefinitionTest extends MarathonSpec with Matchers {

  val fullVersion = AppDefinition.VersionInfo.forNewConfig(Timestamp(1))

  test("ToProto") {
    val app1 = AppDefinition(
      id = "play".toPath,
      cmd = Some("bash foo-*/start -Dhttp.port=$PORT"),
      cpus = 4.0,
      mem = 256.0,
      instances = 5,
      portDefinitions = PortDefinitions(8080, 8081),
      executor = "//cmd",
      acceptedResourceRoles = Some(Set("a", "b"))
    )

    val proto1 = app1.toProto
    assert("play" == proto1.getId)
    assert(proto1.getCmd.hasValue)
    assert(proto1.getCmd.getShell)
    assert("bash foo-*/start -Dhttp.port=$PORT" == proto1.getCmd.getValue)
    assert(5 == proto1.getInstances)
    assert(Seq(8080, 8081) == proto1.getPortDefinitionsList.asScala.map(_.getNumber))
    assert("//cmd" == proto1.getExecutor)
    assert(4 == getScalarResourceValue(proto1, "cpus"), 1e-6)
    assert(256 == getScalarResourceValue(proto1, "mem"), 1e-6)
    assert("bash foo-*/start -Dhttp.port=$PORT" == proto1.getCmd.getValue)
    assert(!proto1.hasContainer)
    assert(1.0 == proto1.getUpgradeStrategy.getMinimumHealthCapacity)
    assert(1.0 == proto1.getUpgradeStrategy.getMaximumOverCapacity)
    assert(proto1.hasAcceptedResourceRoles)
    assert(proto1.getAcceptedResourceRoles == Protos.ResourceRoles.newBuilder().addRole("a").addRole("b").build())

    val app2 = AppDefinition(
      id = "play".toPath,
      cmd = None,
      args = Some(Seq("a", "b", "c")),
      container = Some(
        Container(docker = Some(Container.Docker("group/image")))
      ),
      cpus = 4.0,
      mem = 256.0,
      instances = 5,
      portDefinitions = PortDefinitions(8080, 8081),
      executor = "//cmd",
      upgradeStrategy = UpgradeStrategy(0.7, 0.4)
    )

    val proto2 = app2.toProto
    assert("play" == proto2.getId)
    assert(!proto2.getCmd.hasValue)
    assert(!proto2.getCmd.getShell)
    assert(Seq("a", "b", "c") == proto2.getCmd.getArgumentsList.asScala)
    assert(5 == proto2.getInstances)
    assert(Seq(8080, 8081) == proto2.getPortDefinitionsList.asScala.map(_.getNumber))
    assert("//cmd" == proto2.getExecutor)
    assert(4 == getScalarResourceValue(proto2, "cpus"), 1e-6)
    assert(256 == getScalarResourceValue(proto2, "mem"), 1e-6)
    assert(proto2.hasContainer)
    assert(0.7 == proto2.getUpgradeStrategy.getMinimumHealthCapacity)
    assert(0.4 == proto2.getUpgradeStrategy.getMaximumOverCapacity)
    assert(!proto2.hasAcceptedResourceRoles)
  }

  test("CMD to proto and back again") {
    val app = AppDefinition(
      id = "play".toPath,
      cmd = Some("bash foo-*/start -Dhttp.port=$PORT"),
      versionInfo = fullVersion
    )

    val proto = app.toProto
    proto.getId should be("play")
    proto.getCmd.hasValue should be(true)
    proto.getCmd.getShell should be(true)
    proto.getCmd.getValue should be("bash foo-*/start -Dhttp.port=$PORT")

    val read = AppDefinition().mergeFromProto(proto)
    read should be(app)
  }

  test("ARGS to proto and back again") {
    val app = AppDefinition(
      id = "play".toPath,
      args = Some(Seq("bash", "foo-*/start", "-Dhttp.port=$PORT")),
      versionInfo = fullVersion
    )

    val proto = app.toProto
    proto.getId should be("play")
    proto.getCmd.hasValue should be(true)
    proto.getCmd.getShell should be(false)
    proto.getCmd.getValue should be("bash")
    proto.getCmd.getArgumentsList.asScala should be(Seq("bash", "foo-*/start", "-Dhttp.port=$PORT"))

    val read = AppDefinition().mergeFromProto(proto)
    read should be(app)
  }

  test("ipAddress to proto and back again") {
    val app = AppDefinition(
      id = "app-with-ip-address".toPath,
      cmd = Some("sleep 30"),
      portDefinitions = Nil,
      ipAddress = Some(
        IpAddress(
          groups = Seq("a", "b", "c"),
          labels = Map(
            "foo" -> "bar",
            "baz" -> "buzz"
          )
        )
      )
    )

    val proto = app.toProto
    proto.getId should be("app-with-ip-address")
    proto.hasIpAddress should be (true)

    val read = AppDefinition().mergeFromProto(proto)
    read should be(app)
  }

  test("ipAddress discovery to proto and back again") {
    val app = AppDefinition(
      id = "app-with-ip-address".toPath,
      cmd = Some("sleep 30"),
      portDefinitions = Nil,
      ipAddress = Some(
        IpAddress(
          groups = Seq("a", "b", "c"),
          labels = Map(
            "foo" -> "bar",
            "baz" -> "buzz"
          ),
          discoveryInfo = DiscoveryInfo(
            ports = Vector(DiscoveryInfo.Port(name = "http", number = 80, protocol = "tcp"))
          )
        )
      )
    )

    val proto = app.toProto

    proto.getIpAddress.hasDiscoveryInfo should be (true)
    proto.getIpAddress.getDiscoveryInfo.getPortsList.size() should be (1)
    val read = AppDefinition().mergeFromProto(proto)
    read should equal(app)
  }

  test("MergeFromProto") {
    val cmd = mesos.CommandInfo.newBuilder
      .setValue("bash foo-*/start -Dhttp.port=$PORT")

    val proto1 = ServiceDefinition.newBuilder
      .setId("play")
      .setCmd(cmd)
      .setInstances(3)
      .setExecutor("//cmd")
      .setVersion(Timestamp.now().toString)
      .build

    val app1 = AppDefinition().mergeFromProto(proto1)

    assert("play" == app1.id.toString)
    assert(3 == app1.instances)
    assert("//cmd" == app1.executor)
    assert(Some("bash foo-*/start -Dhttp.port=$PORT") == app1.cmd)
  }

  test("Read obsolete ports from proto") {
    val cmd = mesos.CommandInfo.newBuilder.setValue("bash foo-*/start -Dhttp.port=$PORT")

    val proto1 = ServiceDefinition.newBuilder
      .setId("/app")
      .setCmd(cmd)
      .setInstances(1)
      .setExecutor("//cmd")
      .setVersion(Timestamp.now().toString)
      .addPorts(1000)
      .addPorts(1001)
      .build

    val app = AppDefinition().mergeFromProto(proto1)

    assert(PortDefinitions(1000, 1001) == app.portDefinitions)
  }

  test("ProtoRoundtrip") {
    val app1 = AppDefinition(
      id = "play".toPath,
      cmd = Some("bash foo-*/start -Dhttp.port=$PORT"),
      cpus = 4.0,
      mem = 256.0,
      instances = 5,
      portDefinitions = PortDefinitions(8080, 8081),
      executor = "//cmd",
      labels = Map(
        "one" -> "aaa",
        "two" -> "bbb",
        "three" -> "ccc"
      ),
      versionInfo = fullVersion
    )
    val result1 = AppDefinition().mergeFromProto(app1.toProto)
    assert(result1 == app1)

    val app2 = AppDefinition(
      cmd = None,
      args = Some(Seq("a", "b", "c")),
      versionInfo = fullVersion
    )
    val result2 = AppDefinition().mergeFromProto(app2.toProto)
    assert(result2 == app2)
  }

  test("portAssignments with IP-per-task defining ports") {
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(
        IpAddress(discoveryInfo = DiscoveryInfo(Seq(DiscoveryInfo.Port(80, "http", "tcp"))))
      )

    val task = MarathonTestHelper.mininimalTask(app.id)
      .withNetworkInfos(
        Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress("192.168.0.1"))))
      .withHostPorts(Seq(1))

    val maybePortAssignments = app.portAssignments(task)
    assert(maybePortAssignments.isDefined)

    val portAssignment = maybePortAssignments.get.head
    assert(portAssignment == PortAssignment(
      portName = Some("http"),
      portIndex = 0,
      effectiveIpAddress = "192.168.0.1",
      effectiveHostPort = 1)
    )
  }

  test("portAssignments with IP-per-task defining ports, but a task which doesn't have an IP address yet") {
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(
        IpAddress(discoveryInfo = DiscoveryInfo(Seq(DiscoveryInfo.Port(80, "http", "tcp"))))
      )

    val task = MarathonTestHelper.mininimalTask(app.id)
      .withNetworkInfos(Seq.empty)
      .withHostPorts(Seq.empty)

    val maybePortAssignments = app.portAssignments(task)
    assert(maybePortAssignments.isEmpty)
  }

  test("portAssignments with IP-per-task without ports") {
    val app = MarathonTestHelper.makeBasicApp()
      .withNoPortDefinitions()
      .withIpAddress(
        IpAddress(discoveryInfo = DiscoveryInfo(Seq.empty))
      )

    val task = MarathonTestHelper.mininimalTask(app.id)
      .withNetworkInfos(
        Seq(MarathonTestHelper.networkInfoWithIPAddress(MarathonTestHelper.mesosIpAddress("192.168.0.1"))))
      .withHostPorts(Seq.empty)

    val maybePortAssignments = app.portAssignments(task)
    assert(maybePortAssignments.isDefined)
    assert(maybePortAssignments.get.isEmpty)
  }

  test("portAssignments with a reserved task") {
    val app = MarathonTestHelper.makeBasicApp()
    val task = MarathonTestHelper.minimalReservedTask(app.id, MarathonTestHelper.newReservation)

    val maybePortAssignments = app.portAssignments(task)
    assert(maybePortAssignments.isEmpty)
  }

  test("portAssignments with port mappings") {
    val app = MarathonTestHelper.makeBasicApp().copy(
      container = Some(Container(
        docker = Some(Docker(
          image = "mesosphere/marathon",
          network = Some(mesos.ContainerInfo.DockerInfo.Network.BRIDGE),
          portMappings = Some(Seq(
            Docker.PortMapping(containerPort = 80, hostPort = 0, servicePort = 0, protocol = "tcp",
              name = Some("http"))
          ))
        ))
      )),
      portDefinitions = Seq.empty)

    val task = MarathonTestHelper.mininimalTask(app.id).withHostPorts(Seq(1))

    val maybePortAssignments = app.portAssignments(task)
    assert(maybePortAssignments.isDefined)

    val portAssignment = maybePortAssignments.get.head
    assert(portAssignment == PortAssignment(
      portName = Some("http"),
      portIndex = 0,
      effectiveIpAddress = task.agentInfo.host,
      effectiveHostPort = 1)
    )
  }

  test("portAssignments with bridge network and no port mappings") {
    val app = MarathonTestHelper.makeBasicApp().copy(
      container = Some(Container(
        docker = Some(Docker(
          image = "mesosphere/marathon",
          network = Some(mesos.ContainerInfo.DockerInfo.Network.BRIDGE),
          portMappings = Some(Seq.empty))))
      ),
      portDefinitions = Seq.empty)

    val task = MarathonTestHelper.mininimalTask(app.id).withHostPorts(Seq(1))

    val maybePortAssignments = app.portAssignments(task)
    assert(maybePortAssignments.isDefined)
    assert(maybePortAssignments.head.isEmpty)
  }

  test("portAssignments with port definitions") {
    import MarathonTestHelper.Implicits._

    val app = MarathonTestHelper.makeBasicApp()
      .withPortDefinitions(Seq(PortDefinition(port = 0, protocol = "tcp", name = Some("http"), labels = Map.empty)))

    val task = MarathonTestHelper.mininimalTask(app.id).withHostPorts(Seq(1))

    val maybePortAssignments = app.portAssignments(task)
    assert(maybePortAssignments.isDefined)

    val portAssignment = maybePortAssignments.get.head
    assert(portAssignment == PortAssignment(
      portName = Some("http"),
      portIndex = 0,
      effectiveIpAddress = task.agentInfo.host,
      effectiveHostPort = 1)
    )
  }

  test("portAssignments with absolutely no ports") {
    import MarathonTestHelper.Implicits._

    val app = MarathonTestHelper.makeBasicApp().withNoPortDefinitions()

    val task = MarathonTestHelper.mininimalTask(app.id).withHostPorts(Seq(1))

    val maybePortAssignments = app.portAssignments(task)
    assert(maybePortAssignments.isDefined)
    assert(maybePortAssignments.get.isEmpty)
  }

  def getScalarResourceValue(proto: ServiceDefinition, name: String) = {
    proto.getResourcesList.asScala
      .find(_.getName == name)
      .get.getScalar.getValue
  }
}
