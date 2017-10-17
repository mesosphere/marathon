package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.api.serialization.VolumeSerializer
import mesosphere.marathon.state.{ DiskType, DockerVolume, ExternalVolumeInfo, PersistentVolumeInfo }
import org.apache.mesos.{ Protos => Mesos }

class VolumeConversionTest extends UnitTest {

  def convertToProtobufThenToRAML(volume: => state.Volume, raml: => AppVolume): Unit = {
    "convert to protobuf, then to RAML" in {
      val proto = VolumeSerializer.toProto(volume)
      val proto2Raml = proto.toRaml
      proto2Raml should be(raml)
    }
  }

  "core DockerVolume conversion" when {
    val volume = state.DockerVolume("/container", "/host", Mesos.Volume.Mode.RW)
    "converting to RAML" should {
      val raml = volume.toRaml[AppVolume]
      behave like convertToProtobufThenToRAML(volume, raml)
      "convert all fields to RAML" in {
        raml shouldBe a[AppDockerVolume]
        val ramlDocker = raml.asInstanceOf[AppDockerVolume]
        ramlDocker.containerPath should be(volume.containerPath)
        ramlDocker.hostPath should be(volume.hostPath)
        ramlDocker.mode should be(ReadMode.Rw)
      }
    }
  }

  "RAML docker volume conversion" when {
    val volume = AppDockerVolume(containerPath = "/container", hostPath = "/host", mode = ReadMode.Rw)
    "converting to core DockerVolume" should {
      val dockerVolume: DockerVolume = Some(volume.fromRaml).collect {
        case v: DockerVolume => v
      }.getOrElse(fail("expected docker volume"))

      "convert all fields from RAML to core" in {
        dockerVolume.containerPath should be(volume.containerPath)
        dockerVolume.hostPath should be(volume.hostPath)
        dockerVolume.mode should be(Mesos.Volume.Mode.RW)
      }
    }
  }

  "core ExternalVolume conversion" when {
    val external = ExternalVolumeInfo(Some(123L), "external", "foo", Map("foo" -> "bla"))
    val volume = state.ExternalVolume("/container", external, Mesos.Volume.Mode.RW)
    "converting to RAML" should {
      val raml = volume.toRaml[AppVolume]
      behave like convertToProtobufThenToRAML(volume, raml)
      "convert all fields to RAML" in {
        raml shouldBe a[AppExternalVolume]
        val externalRaml = raml.asInstanceOf[AppExternalVolume]
        externalRaml.containerPath should be(volume.containerPath)
        externalRaml.mode should be(ReadMode.Rw)
        externalRaml.external.name should be(Some(external.name))
        externalRaml.external.options should be(external.options)
        externalRaml.external.provider should be(Some(external.provider))
        externalRaml.external.size should be(external.size)
      }
    }
  }

  "RAML external volume conversion" when {
    val volume = AppExternalVolume("/container", ExternalVolume(Some(1L), Some("vol-name"), Some("provider"), Map("foo" -> "bla")), ReadMode.Rw)
    "converting to core ExternalVolume" should {
      val externalVolume: state.ExternalVolume = Some(volume.fromRaml).collect {
        case v: state.ExternalVolume => v
      }.getOrElse(fail("expected ExternalVolume"))
      "covert all fields from RAML to core" in {
        externalVolume.containerPath should be(volume.containerPath)
        externalVolume.mode should be(Mesos.Volume.Mode.RW)
        externalVolume.external.name should be(volume.external.name.head)
        externalVolume.external.provider should be(volume.external.provider.head)
        externalVolume.external.size should be(volume.external.size)
        externalVolume.external.options should be(volume.external.options)
      }
    }
  }

  "core PersistentVolume conversion" when {
    val persistent = PersistentVolumeInfo(123L, Some(1234L), state.DiskType.Path)
    val volume = state.PersistentVolume("/container", persistent, Mesos.Volume.Mode.RW)
    "converting to RAML" should {
      val raml = volume.toRaml[AppVolume]
      behave like convertToProtobufThenToRAML(volume, raml)
      "convert all fields to RAML" in {
        raml shouldBe a[AppPersistentVolume]
        val persistentRaml = raml.asInstanceOf[AppPersistentVolume]
        persistentRaml.containerPath should be(volume.containerPath)
        persistentRaml.mode should be(ReadMode.Rw)
        persistentRaml.persistent.`type` should be(Some(PersistentVolumeType.Path))
        persistentRaml.persistent.size should be(persistent.size)
        persistentRaml.persistent.maxSize should be(persistent.maxSize)
        persistentRaml.persistent.constraints should be(empty)
      }
    }
  }

  "RAML persistent volume conversion" when {
    val volume = AppPersistentVolume(
      "/container",
      PersistentVolume(None, size = 123L, maxSize = Some(1234L), constraints = Set.empty), ReadMode.Rw)
    "converting from RAML" should {
      val persistent = Some(volume.fromRaml).collect {
        case v: state.PersistentVolume => v
      }.getOrElse(fail("expected PersistentVolume"))
      "convert all fields to core" in {
        persistent.containerPath should be(volume.containerPath)
        persistent.mode should be(Mesos.Volume.Mode.RW)
        persistent.persistent.`type` should be(DiskType.Root)
        persistent.persistent.size should be(volume.persistent.size)
        persistent.persistent.maxSize should be(volume.persistent.maxSize)
        persistent.persistent.constraints should be(Set.empty)
      }
    }
  }
}
