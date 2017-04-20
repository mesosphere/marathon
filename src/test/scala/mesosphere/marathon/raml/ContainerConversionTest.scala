package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.api.serialization.ContainerSerializer
import org.apache.mesos.{ Protos => Mesos }

class ContainerConversionTest extends UnitTest {

  def convertToProtobufThenToRAML(container: => state.Container, raml: => Container): Unit = {
    "convert to protobuf, then to RAML" in {
      val proto = ContainerSerializer.toProto(container)
      val proto2Raml = proto.toRaml
      proto2Raml should be(raml)
    }
  }

  "A Mesos Plain container is converted" when {
    "a mesos container" should {
      val container = state.Container.Mesos(
        volumes = Seq(coreHostVolume),
        portMappings = Seq(corePortMapping))
      val raml = container.toRaml[Container]

      behave like convertToProtobufThenToRAML(container, raml)

      "convert to a RAML container" in {
        raml.`type` should be(EngineType.Mesos)
        raml.appc should be(empty)
        raml.docker should be(empty)
        raml.volumes should be(Seq(ramlHostVolume))
        raml.portMappings should contain(Seq(ramlPortMapping))
      }
    }
    "a RAML container" should {
      "convert to a mesos container" in {
        val container = Container(EngineType.Mesos, portMappings = Option(Seq(ramlPortMapping)), volumes = Seq(ramlHostVolume))
        val mc = Some(container.fromRaml).collect {
          case c: state.Container.Mesos => c
        }.getOrElse(fail("expected Container.Mesos"))
        mc.portMappings should be(Seq(corePortMapping))
        mc.volumes should be(Seq(coreHostVolume))
      }
    }
  }

  "A Mesos Docker container is converted" when {
    "a mesos-docker container" should {
      val container = state.Container.MesosDocker(Seq(coreHostVolume), "test", Seq(corePortMapping), Some(credentials))
      val raml = container.toRaml[Container]

      behave like convertToProtobufThenToRAML(container, raml)

      "convert to a RAML container" in {
        raml.`type` should be(EngineType.Mesos)
        raml.appc should be(empty)
        raml.volumes should be(Seq(ramlHostVolume))
        raml.portMappings should contain(Seq(ramlPortMapping))
        raml.docker should be(defined)
        raml.docker.get.image should be("test")
        raml.docker.get.credential should be(defined)
        raml.docker.get.credential.get.principal should be(credentials.principal)
        raml.docker.get.credential.get.secret should be(credentials.secret)
      }
    }
    "a mesos-docker container w/o port mappings" should {
      val container = state.Container.MesosDocker(Seq(coreHostVolume), "test", portMappings = Seq.empty, Some(credentials))
      val raml = container.toRaml[Container]
      behave like convertToProtobufThenToRAML(container, raml)
    }
    "a RAML container" should {
      "convert to a mesos-docker container" in {
        val container = Container(EngineType.Mesos, portMappings = Option(Seq(ramlPortMapping)), docker = Some(DockerContainer(
          image = "foo", credential = Some(DockerCredentials(credentials.principal, credentials.secret)))),
          volumes = Seq(ramlHostVolume))
        val mc = Some(container.fromRaml).collect {
          case c: state.Container.MesosDocker => c
        }.getOrElse(fail("expected Container.MesosDocker"))
        mc.portMappings should be(Seq(corePortMapping))
        mc.volumes should be(Seq(coreHostVolume))
        mc.image should be("foo")
        mc.credential should be(Some(credentials))
        mc.forcePullImage should be(container.docker.head.forcePullImage)
      }
    }
  }

  "A Mesos AppC container is created correctly" when {
    "a mesos-appc container" should {
      val container = state.Container.MesosAppC(Seq(coreHostVolume), "test", Seq(corePortMapping), Some("id"))
      val raml = container.toRaml[Container]

      behave like convertToProtobufThenToRAML(container, raml)

      "convert to a RAML container" in {
        raml.`type` should be(EngineType.Mesos)
        raml.volumes should be(Seq(ramlHostVolume))
        raml.portMappings should contain(Seq(ramlPortMapping))
        raml.docker should be(empty)
        raml.appc should be(defined)
        raml.appc.get.image should be("test")
        raml.appc.get.id should be(Some("id"))
      }
    }
    "a RAML container" should {
      "convert to a mesos-appc container" in {
        val container = Container(
          EngineType.Mesos, portMappings = Option(Seq(ramlPortMapping)), appc = Some(AppCContainer(image = "foo")),
          volumes = Seq(ramlHostVolume))
        val mc = Some(container.fromRaml).collect {
          case c: state.Container.MesosAppC => c
        }.getOrElse(fail("expected Container.MesosAppC"))
        mc.portMappings should be(Seq(corePortMapping))
        mc.volumes should be(Seq(coreHostVolume))
        mc.image should be("foo")
        mc.forcePullImage should be(container.appc.head.forcePullImage)
        mc.id should be(container.appc.head.id)
        mc.labels should be(empty)
      }
    }
  }

  "A Docker Docker container is created correctly" when {
    "a legacy docker protobuf container (host)" should {
      "convert to RAML" in {
        val legacyProto = Protos.ExtendedContainerInfo.newBuilder()
          .setType(Mesos.ContainerInfo.Type.DOCKER)
          .setDocker(Protos.ExtendedContainerInfo.DockerInfo.newBuilder()
            .setImage("image0")
            .setOBSOLETENetwork(Mesos.ContainerInfo.DockerInfo.Network.HOST)
          )
          .build
        val expectedRaml = Container(
          `type` = EngineType.Docker,
          docker = Option(DockerContainer(
            image = "image0",
            network = Option(DockerNetwork.Host),
            portMappings = None
          )),
          portMappings = Option(Seq.empty)
        )
        legacyProto.toRaml[Container] should be(expectedRaml)
      }
    }
    "a legacy docker protobuf container (user)" should {
      "convert to RAML" in {
        val legacyProto = Protos.ExtendedContainerInfo.newBuilder()
          .setType(Mesos.ContainerInfo.Type.DOCKER)
          .setDocker(Protos.ExtendedContainerInfo.DockerInfo.newBuilder()
            .setImage("image0")
            .setOBSOLETENetwork(Mesos.ContainerInfo.DockerInfo.Network.USER)
          )
          .build
        val expectedRaml = Container(
          `type` = EngineType.Docker,
          docker = Option(DockerContainer(
            image = "image0",
            network = Option(DockerNetwork.User),
            portMappings = Option(Seq.empty)
          )),
          portMappings = Option(Seq.empty)
        )
        legacyProto.toRaml[Container] should be(expectedRaml)
      }
    }
    "a legacy docker protobuf container (bridge)" should {
      "convert to RAML" in {
        val legacyProto = Protos.ExtendedContainerInfo.newBuilder()
          .setType(Mesos.ContainerInfo.Type.DOCKER)
          .setDocker(Protos.ExtendedContainerInfo.DockerInfo.newBuilder()
            .setImage("image0")
            .setOBSOLETENetwork(Mesos.ContainerInfo.DockerInfo.Network.BRIDGE)
            .addOBSOLETEPortMappings(
              Protos.ExtendedContainerInfo.DockerInfo.ObsoleteDockerPortMapping.newBuilder()
              .setName("http").setContainerPort(1).setHostPort(2).setServicePort(3)
              .addLabels(Mesos.Label.newBuilder().setKey("foo").setValue("bar"))
              .build
            )
          )
          .build
        val expectedRaml = Container(
          `type` = EngineType.Docker,
          docker = Option(DockerContainer(
            image = "image0",
            network = Option(DockerNetwork.Bridge),
            portMappings = Option(Seq(
              ContainerPortMapping(
                containerPort = 1,
                hostPort = Option(2),
                labels = Map("foo" -> "bar"),
                name = Option("http"),
                servicePort = 3
              ))
            )
          )),
          portMappings = None
        )
        legacyProto.toRaml[Container] should be(expectedRaml)
      }
    }
    "a docker-docker container" should {
      val container = state.Container.Docker(Seq(coreHostVolume), "test", Seq(corePortMapping))
      val raml = container.toRaml[Container]

      behave like convertToProtobufThenToRAML(container, raml)

      "convert to a RAML container" in {
        raml.`type` should be(EngineType.Docker)
        raml.appc should be(empty)
        raml.volumes should be(Seq(ramlHostVolume))
        raml.docker should be(defined)
        raml.docker.get.image should be("test")
        raml.docker.get.credential should be(empty)
        raml.docker.get.network should be(empty)
        raml.portMappings should contain(Seq(ramlPortMapping))
      }
    }
    "a docker-docker container w/o port mappings" should {
      val container = state.Container.Docker(Seq(coreHostVolume), "test")
      val raml = container.toRaml[Container]
      behave like convertToProtobufThenToRAML(container, raml)
    }
    "a RAML container" should {
      "convert to a docker-docker container" in {
        val container = Container(EngineType.Docker, portMappings = Option(Seq(ramlPortMapping)), docker = Some(DockerContainer(
          image = "foo", parameters = Seq(DockerParameter("qws", "erf")))), volumes = Seq(ramlHostVolume))
        val mc = Some(container.fromRaml).collect {
          case c: state.Container.Docker => c
        }.getOrElse(fail("expected Container.Docker"))
        mc.portMappings should be(Seq(corePortMapping))
        mc.volumes should be(Seq(coreHostVolume))
        mc.image should be("foo")
        mc.forcePullImage should be(container.docker.head.forcePullImage)
        mc.parameters should be(Seq(state.Parameter("qws", "erf")))
        mc.privileged should be(container.docker.head.privileged)
      }
    }
  }

  private lazy val credentials = state.Container.Credential("principal", Some("secret"))
  private lazy val ramlPortMapping = ContainerPortMapping(
    containerPort = 80,
    hostPort = Some(90),
    servicePort = 100,
    name = Some("pok"),
    labels = Map("wer" -> "rty")
  )
  private lazy val corePortMapping = state.Container.PortMapping(
    containerPort = 80,
    hostPort = Some(90),
    servicePort = 100,
    name = Some("pok"),
    labels = Map("wer" -> "rty")
  )
  private lazy val coreHostVolume = state.DockerVolume("cpath", "/host/path", Mesos.Volume.Mode.RW)
  private lazy val ramlHostVolume = AppVolume("cpath", Option("/host/path"), mode = ReadMode.Rw)
}
