package mesosphere.marathon.state

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.api.serialization.{
  PortMappingSerializer,
  VolumeSerializer,
  DockerSerializer,
  ContainerSerializer
}
import com.wix.accord._
import org.scalatest.Matchers
import org.apache.mesos.{ Protos => mesos }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.collection.JavaConverters._

class ContainerTest extends MarathonSpec with Matchers {
  import mesosphere.marathon.api.v2.json.Formats._

  class Fixture {
    lazy val volumes = Seq(
      DockerVolume("/etc/a", "/var/data/a", mesos.Volume.Mode.RO),
      DockerVolume("/etc/b", "/var/data/b", mesos.Volume.Mode.RW)
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
            Container.Docker.PortMapping(
              containerPort = 8080,
              hostPort = 32001,
              servicePort = 9000,
              protocol = Container.Docker.PortMapping.TCP,
              name = Some("http"),
              labels = Map("foo" -> "bar")),
            Container.Docker.PortMapping(
              containerPort = 8081,
              hostPort = 32002,
              servicePort = 9001,
              protocol = Container.Docker.PortMapping.UDP)
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

    lazy val container5 = Container(
      `type` = mesos.ContainerInfo.Type.DOCKER,
      volumes = Nil,
      docker = Some(
        Container.Docker(
          image = "group/image",
          network = Some(mesos.ContainerInfo.DockerInfo.Network.BRIDGE),
          portMappings = Some(Seq(
            Container.Docker.PortMapping(
              containerPort = 8080,
              hostPort = 32001,
              servicePort = 9000,
              protocol = "tcp,udp"),
            Container.Docker.PortMapping(
              containerPort = 8081,
              hostPort = 32002,
              servicePort = 9001,
              protocol = "udp,tcp")
          ))
        )
      )
    )

    lazy val mesosContainerWithPersistentVolume = Container(
      `type` = mesos.ContainerInfo.Type.MESOS,
      volumes = Seq[Volume](
        PersistentVolume(
          containerPath = "/local/container/",
          persistent = PersistentVolumeInfo(1024),
          mode = mesos.Volume.Mode.RW
        )
      ),
      docker = None
    )

    lazy val mesosContainerWithPersistentVolumeJsonStr =
      """
        |{
        |  "id": "test",
        |  "container": {
        |     "type": "MESOS",
        |     "volumes": [{
        |        "containerPath": "/local/container/",
        |        "mode": "RW",
        |        "persistent": {
        |           "size": 1024
        |        }
        |     }]
        |  }
        |}""".stripMargin

  }

  def fixture(): Fixture = new Fixture

  test("ToProto") {
    val f = fixture()
    val proto = ContainerSerializer.toProto(f.container)
    assert(mesos.ContainerInfo.Type.DOCKER == proto.getType)
    assert("group/image" == proto.getDocker.getImage)
    assert(f.container.volumes == proto.getVolumesList.asScala.map(Volume(_)))
    assert(proto.getDocker.hasForcePullImage)
    assert(f.container.docker.get.forcePullImage == proto.getDocker.getForcePullImage)

    val proto2: mesosphere.marathon.Protos.ExtendedContainerInfo = ContainerSerializer.toProto(f.container2)
    assert(mesos.ContainerInfo.Type.DOCKER == proto2.getType)
    assert("group/image" == proto2.getDocker.getImage)
    assert(f.container2.docker.get.network == Some(proto2.getDocker.getNetwork))
    val portMappings = proto2.getDocker.getPortMappingsList.asScala
    assert(f.container2.docker.get.portMappings == Some(portMappings.map(PortMappingSerializer.fromProto)))
    assert(proto2.getDocker.hasForcePullImage)
    assert(f.container2.docker.get.forcePullImage == proto2.getDocker.getForcePullImage)

    val proto3 = ContainerSerializer.toProto(f.container3)
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

    val proto5 = ContainerSerializer.toProto(f.container5)
    assert(proto5.getDocker.getPortMappingsCount == 2)
  }

  test("ToMesos") {
    val f = fixture()
    val proto = ContainerSerializer.toMesos(f.container)

    assert(mesos.ContainerInfo.Type.DOCKER == proto.getType)
    assert("group/image" == proto.getDocker.getImage)
    assert(proto.getDocker.hasForcePullImage)
    assert(f.container.docker.get.forcePullImage == proto.getDocker.getForcePullImage)

    val proto2 = ContainerSerializer.toMesos(f.container2)
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

    val proto3 = ContainerSerializer.toMesos(f.container3)
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

    val proto5 = ContainerSerializer.toMesos(f.container5)
    assert(proto5.getDocker.getPortMappingsCount == 4)
    val pms5 = proto5.getDocker.getPortMappingsList.asScala
    assert(pms5.groupBy(_.getHostPort).contains(32001))
    assert(pms5.groupBy(_.getHostPort).contains(32002))
    assert(pms5.groupBy(_.getHostPort)(32001).map(_.getProtocol).toSet == Set("tcp", "udp"))
    assert(pms5.groupBy(_.getHostPort)(32002).map(_.getProtocol).toSet == Set("tcp", "udp"))
  }

  test("ConstructFromProto") {
    val f = fixture()

    val containerInfo = Protos.ExtendedContainerInfo.newBuilder
      .setType(mesos.ContainerInfo.Type.DOCKER)
      .addAllVolumes(f.volumes.map(VolumeSerializer.toProto).asJava)
      .setDocker(DockerSerializer.toProto(f.container.docker.get))
      .build

    val container = ContainerSerializer.fromProto(containerInfo)
    assert(container == f.container)

    val containerInfo2 = Protos.ExtendedContainerInfo.newBuilder
      .setType(mesos.ContainerInfo.Type.DOCKER)
      .setDocker(DockerSerializer.toProto(f.container2.docker.get))
      .build

    val container2 = ContainerSerializer.fromProto(containerInfo2)
    assert(container2 == f.container2)

    val containerInfo3 = Protos.ExtendedContainerInfo.newBuilder
      .setType(mesos.ContainerInfo.Type.DOCKER)
      .setDocker(DockerSerializer.toProto(f.container3.docker.get))
      .build

    val container3 = ContainerSerializer.fromProto(containerInfo3)
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
            { "containerPort": 8080, "hostPort": 32001, "servicePort": 9000, "protocol": "tcp", "name": "http",
              "labels": { "foo": "bar" } },
            { "containerPort": 8081, "hostPort": 32002, "servicePort": 9001, "protocol": "udp" }
          ]
        }
      }
      """

    val readResult4 = fromJson(json4)
    val f = fixture()
    assert(readResult4 == f.container2)
  }

  test("Reading JSON with invalid-protocol portMappings") {
    val json =
      """
      {
        "type": "DOCKER",
        "docker": {
          "image": "group/image",
          "network": "BRIDGE",
          "portMappings": [
            { "containerPort": 8080, "hostPort": 32001, "servicePort": 9000, "protocol": "http" }
          ]
        }
      }
      """
    assert(validate(fromJson(json)).isFailure)
  }

  test("Reading JSON with multi-protocol portMappings") {
    val json5 =
      """
      {
        "type": "DOCKER",
        "docker": {
          "image": "group/image",
          "network": "BRIDGE",
          "portMappings": [
            { "containerPort": 8080, "hostPort": 32001, "servicePort": 9000, "protocol": "tcp,udp" },
            { "containerPort": 8081, "hostPort": 32002, "servicePort": 9001, "protocol": "udp,tcp" }
          ]
        }
      }
      """

    val readResult5 = fromJson(json5)
    val f = fixture()
    assert(readResult5 == f.container5)
  }

  test("Reading JSON with non-unique multi-protocol portMappings") {
    val json =
      """
      {
        "type": "DOCKER",
        "docker": {
          "image": "group/image",
          "network": "BRIDGE",
          "portMappings": [
            { "containerPort": 8080, "hostPort": 32001, "servicePort": 9000, "protocol": "tcp,udp,udp" }
          ]
        }
      }
      """
    assert(validate(fromJson(json)).isFailure)
  }

  test("SerializationRoundTrip with privileged, networking and parameters") {
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

  test("Reading JSON with multiple parameters with the same name") {
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

  test("""FromJSON with Mesos ContainerInfo should parse successfully""") {
    val f = new Fixture
    val appDef = Json.parse(f.mesosContainerWithPersistentVolumeJsonStr).as[AppDefinition]
    val expectedContainer = f.mesosContainerWithPersistentVolume

    appDef.container should equal(Some(expectedContainer))
  }

  test("""ToJson should correctly handle container type MESOS""") {
    val f = new Fixture
    val appDefinition = AppDefinition(id = PathId("test"), container = Some(f.mesosContainerWithPersistentVolume))
    val json = Json.toJson(appDefinition)
    (json \ "container" \ "type").asOpt[String] should be(Some("MESOS"))
  }

}
