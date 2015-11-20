package mesosphere.marathon.state

import com.google.common.collect.Lists
import mesosphere.marathon.Protos.ServiceDefinition
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.{ MarathonSpec, Protos }
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
      ports = Seq(8080, 8081),
      executor = "//cmd",
      acceptedResourceRoles = Some(Set("a", "b"))
    )

    val proto1 = app1.toProto
    assert("play" == proto1.getId)
    assert(proto1.getCmd.hasValue)
    assert(proto1.getCmd.getShell)
    assert("bash foo-*/start -Dhttp.port=$PORT" == proto1.getCmd.getValue)
    assert(5 == proto1.getInstances)
    assert(Lists.newArrayList(8080, 8081) == proto1.getPortsList)
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
      ports = Seq(8080, 8081),
      executor = "//cmd",
      upgradeStrategy = UpgradeStrategy(0.7, 0.4)
    )

    val proto2 = app2.toProto
    assert("play" == proto2.getId)
    assert(!proto2.getCmd.hasValue)
    assert(!proto2.getCmd.getShell)
    assert(Seq("a", "b", "c") == proto2.getCmd.getArgumentsList.asScala)
    assert(5 == proto2.getInstances)
    assert(Lists.newArrayList(8080, 8081) == proto2.getPortsList)
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
      ports = Nil,
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

  test("ProtoRoundtrip") {
    val app1 = AppDefinition(
      id = "play".toPath,
      cmd = Some("bash foo-*/start -Dhttp.port=$PORT"),
      cpus = 4.0,
      mem = 256.0,
      instances = 5,
      ports = Seq(8080, 8081),
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

  def getScalarResourceValue(proto: ServiceDefinition, name: String) = {
    proto.getResourcesList.asScala
      .find(_.getName == name)
      .get.getScalar.getValue
  }
}
