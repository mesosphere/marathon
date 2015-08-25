package mesosphere.marathon.state

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos
import mesosphere.marathon.api.JsonTestHelper
import org.scalatest.Matchers
import org.apache.mesos.{ Protos => mesos }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._

class ContainerTest extends MarathonSpec with Matchers {
  import mesosphere.marathon.api.v2.json.Formats._

  class Fixture {
    lazy val volumes = Seq(
      Container.Volume("/etc/a", "/var/data/a", mesos.Volume.Mode.RO),
      Container.Volume("/etc/b", "/var/data/b", mesos.Volume.Mode.RW)
    )

    lazy val container = Container(
      `type` = mesos.ContainerInfo.Type.DOCKER,
      volumes = volumes,
      docker = Some(Container.Docker(image = "group/image"))
    )

    lazy val container2 = Container(
      `type` = mesos.ContainerInfo.Type.DOCKER,
      volumes = Nil,
      docker = Some(
        Container.Docker(
          image = "group/image",
          network = Some(mesos.ContainerInfo.DockerInfo.Network.BRIDGE),
          portMappings = Some(Seq(
            Container.Docker.PortMapping(8080, 32001, 9000, "tcp"),
            Container.Docker.PortMapping(8081, 32002, 9001, "udp")
          )
          )
        )
      )
    )

    lazy val container3 = Container(
      `type` = mesos.ContainerInfo.Type.DOCKER,
      volumes = Nil,
      docker = Some(
        Container.Docker(
          image = "group/image",
          network = Some(mesos.ContainerInfo.DockerInfo.Network.NONE),
          privileged = true,
          parameters = Seq(
            Parameter("abc", "123"),
            Parameter("def", "456")
          )
        )
      )
    )

    lazy val container4 = Container(
      `type` = mesos.ContainerInfo.Type.DOCKER,
      volumes = Nil,
      docker = Some(
        Container.Docker(
          image = "group/image",
          network = Some(mesos.ContainerInfo.DockerInfo.Network.NONE),
          privileged = true,
          parameters = Seq(
            Parameter("abc", "123"),
            Parameter("def", "456"),
            Parameter("def", "789")
          ),
          forcePullImage = true
        )
      )
    )

  }

  def fixture(): Fixture = new Fixture

  test("ToProto") {
    val f = fixture()
    val proto = f.container.toProto
    assert(mesos.ContainerInfo.Type.DOCKER == proto.getType)
    assert("group/image" == proto.getDocker.getImage)
    assert(f.container.volumes == proto.getVolumesList.asScala.map(Container.Volume(_)))
    assert(proto.getDocker.hasForcePullImage)
    assert(f.container.docker.get.forcePullImage == proto.getDocker.getForcePullImage)

    val proto2: mesosphere.marathon.Protos.ExtendedContainerInfo = f.container2.toProto
    assert(mesos.ContainerInfo.Type.DOCKER == proto2.getType)
    assert("group/image" == proto2.getDocker.getImage)
    assert(f.container2.docker.get.network == Some(proto2.getDocker.getNetwork))
    val portMappings = proto2.getDocker.getPortMappingsList.asScala
    assert(f.container2.docker.get.portMappings == Some(portMappings.map(Container.Docker.PortMapping.apply)))
    assert(proto2.getDocker.hasForcePullImage)
    assert(f.container2.docker.get.forcePullImage == proto2.getDocker.getForcePullImage)

    val proto3 = f.container3.toProto
    assert(mesos.ContainerInfo.Type.DOCKER == proto3.getType)
    assert("group/image" == proto3.getDocker.getImage)
    assert(f.container3.docker.get.network == Some(proto3.getDocker.getNetwork))
    assert(f.container3.docker.get.privileged == proto3.getDocker.getPrivileged)
    assert(f.container3.docker.get.parameters.map(_.key) == proto3.getDocker.getParametersList.asScala.map(_.getKey))
    assert(
      f.container3.docker.get.parameters.map(_.value) == proto3.getDocker.getParametersList.asScala.map(_.getValue)
    )
    assert(proto3.getDocker.hasForcePullImage)
    assert(f.container3.docker.get.forcePullImage == proto3.getDocker.getForcePullImage)

  }

  test("ToMesos") {
    val f = fixture()
    val proto = f.container.toMesos
    assert(mesos.ContainerInfo.Type.DOCKER == proto.getType)
    assert("group/image" == proto.getDocker.getImage)
    assert(f.container.volumes == proto.getVolumesList.asScala.map(Container.Volume(_)))
    assert(proto.getDocker.hasForcePullImage)
    assert(f.container.docker.get.forcePullImage == proto.getDocker.getForcePullImage)

    val proto2 = f.container2.toMesos
    assert(mesos.ContainerInfo.Type.DOCKER == proto2.getType)
    assert("group/image" == proto2.getDocker.getImage)
    assert(f.container2.docker.get.network == Some(proto2.getDocker.getNetwork))

    val expectedPortMappings = Seq(
      mesos.ContainerInfo.DockerInfo.PortMapping.newBuilder
        .setContainerPort(8080)
        .setHostPort(32001)
        .setProtocol("tcp")
        .build,
      mesos.ContainerInfo.DockerInfo.PortMapping.newBuilder
        .setContainerPort(8081)
        .setHostPort(32002)
        .setProtocol("udp")
        .build
    )

    assert(expectedPortMappings == proto2.getDocker.getPortMappingsList.asScala)
    assert(proto2.getDocker.hasForcePullImage)
    assert(f.container2.docker.get.forcePullImage == proto2.getDocker.getForcePullImage)

    val proto3 = f.container3.toMesos
    assert(mesos.ContainerInfo.Type.DOCKER == proto3.getType)
    assert("group/image" == proto3.getDocker.getImage)
    assert(f.container3.docker.get.network == Some(proto3.getDocker.getNetwork))
    assert(f.container3.docker.get.privileged == proto3.getDocker.getPrivileged)
    assert(f.container3.docker.get.parameters.map(_.key) == proto3.getDocker.getParametersList.asScala.map(_.getKey))
    assert(
      f.container3.docker.get.parameters.map(_.value) == proto3.getDocker.getParametersList.asScala.map(_.getValue)
    )
    assert(proto3.getDocker.hasForcePullImage)
    assert(f.container3.docker.get.forcePullImage == proto3.getDocker.getForcePullImage)
  }

  test("ConstructFromProto") {
    val f = fixture()

    val containerInfo = Protos.ExtendedContainerInfo.newBuilder
      .setType(mesos.ContainerInfo.Type.DOCKER)
      .addAllVolumes(f.volumes.map(_.toProto).asJava)
      .setDocker(f.container.docker.get.toProto)
      .build

    val container = Container(containerInfo)
    assert(container == f.container)

    val containerInfo2 = Protos.ExtendedContainerInfo.newBuilder
      .setType(mesos.ContainerInfo.Type.DOCKER)
      .setDocker(f.container2.docker.get.toProto)
      .build

    val container2 = Container(containerInfo2)
    assert(container2 == f.container2)

    val containerInfo3 = Protos.ExtendedContainerInfo.newBuilder
      .setType(mesos.ContainerInfo.Type.DOCKER)
      .setDocker(f.container3.docker.get.toProto)
      .build

    val container3 = Container(containerInfo3)
    assert(container3 == f.container3)
  }

  test("SerializationRoundtrip empty") {
    val container1 = Container(`type` = mesos.ContainerInfo.Type.DOCKER)
    JsonTestHelper.assertSerializationRoundtripWorks(container1)
  }

  test("SerializationRoundtrip with slightly more complex data") {
    val f = fixture()
    JsonTestHelper.assertSerializationRoundtripWorks(f.container)
  }

  private[this] def fromJson(json: String): Container = {
    Json.fromJson[Container](Json.parse(json)).get
  }

  test("Reading JSON with volumes") {
    val json3 =
      """
      {
        "type": "DOCKER",
        "docker": {
          "image": "group/image"
        },
        "volumes": [
          {
            "containerPath": "/etc/a",
            "hostPath": "/var/data/a",
            "mode": "RO"
          },
          {
            "containerPath": "/etc/b",
            "hostPath": "/var/data/b",
            "mode": "RW"
          }
        ]
      }
      """

    val readResult3 = fromJson(json3)
    val f = fixture()
    assert (readResult3 == f.container)
  }
  test("Reading JSON with portMappings") {
    val json4 =
      """
      {
        "type": "DOCKER",
        "docker": {
          "image": "group/image",
          "network": "BRIDGE",
          "portMappings": [
            { "containerPort": 8080, "hostPort": 32001, "servicePort": 9000, "protocol": "tcp"},
            { "containerPort": 8081, "hostPort": 32002, "servicePort": 9001, "protocol": "udp"}
          ]
        }
      }
      """

    val readResult4 = fromJson(json4)
    val f = fixture()
    assert(readResult4 == f.container2)
  }

  test("SerializationRoundTrip  with privileged, networking and parameters") {
    JsonTestHelper.assertSerializationRoundtripWorks(fixture().container3)
  }

  test("Reading JSON with privileged, networking and parameters") {
    val json6 =
      """
      {
        "type": "DOCKER",
        "docker": {
          "image": "group/image",
          "network": "NONE",
          "privileged": true,
          "parameters": [
            { "key": "abc", "value": "123" },
            { "key": "def", "value": "456" }
          ]
        }
      }
      """

    val readResult6 = fromJson(json6)
    assert(readResult6 == fixture().container3)
  }

  test("Reading JSON with multiple paramaters with the same name") {
    val json7 =
      """
      {
        "type": "DOCKER",
        "docker": {
          "image": "group/image",
          "network": "NONE",
          "privileged": true,
          "parameters": [
            { "key": "abc", "value": "123" },
            { "key": "def", "value": "456" },
            { "key": "def", "value": "789" }
          ],
          "forcePullImage": true
        }
      }
      """

    val readResult7 = fromJson(json7)
    assert(readResult7 == fixture().container4)
  }

}
