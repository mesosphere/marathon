package mesosphere.marathon.state

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.ServiceDefinition
import mesosphere.marathon.api.ModelValidation
import javax.validation.Validation
import org.scalatest.Matchers
import org.apache.mesos.{ Protos => mesos }

import scala.collection.JavaConverters._

class ContainerTest extends MarathonSpec with Matchers with ModelValidation {

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
  }

  def fixture(): Fixture = new Fixture

  test("ToProto") {
    val f = fixture()
    val proto = f.container.toProto
    assert(mesos.ContainerInfo.Type.DOCKER == proto.getType)
    assert("group/image" == proto.getDocker.getImage)
    assert(f.container.volumes == proto.getVolumesList.asScala.map(Container.Volume(_)))
  }

  test("ConstructFromProto") {
    val f = fixture()

    val containerInfo = mesos.ContainerInfo.newBuilder
      .setType(mesos.ContainerInfo.Type.DOCKER)
      .addAllVolumes(f.volumes.map(_.toProto).asJava)
      .setDocker(f.container.docker.get.toProto)
      .build

    val container = Container(containerInfo)
    assert(container == f.container)
  }

  test("SerializationRoundtrip") {
    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.jackson.CaseClassModule
    import mesosphere.marathon.api.v2.json.MarathonModule

    val f = fixture()

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    val container1 = Container(`type` = mesos.ContainerInfo.Type.DOCKER)
    val json1 = mapper.writeValueAsString(container1)
    val readResult1 = mapper.readValue(json1, classOf[Container])
    assert(readResult1 == container1)

    val json2 = mapper.writeValueAsString(f.container)
    val readResult2 = mapper.readValue(json2, classOf[Container])
    assert(readResult2 == f.container)

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

    val readResult3 = mapper.readValue(json3, classOf[Container])
    assert(readResult3 == f.container)
  }

}
