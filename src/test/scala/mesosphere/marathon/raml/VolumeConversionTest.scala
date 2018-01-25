package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.api.serialization.VolumeSerializer
import mesosphere.marathon.state.{ Volume, VolumeMount }

class VolumeConversionTest extends UnitTest {

  def convertToProtobufThenToRAML(volumeWithMount: => state.VolumeWithMount[Volume], raml: => AppVolume): Unit = {
    "convert to protobuf, then to RAML" in {
      val proto = VolumeSerializer.toProto(volumeWithMount)
      val proto2Raml = proto.toRaml
      proto2Raml should be(raml)
    }
  }

  "core HostVolume conversion" when {
    val hostVolume = state.HostVolume(None, "/host")
    val mount = state.VolumeMount(None, "/container")
    val volume = state.VolumeWithMount(hostVolume, mount)
    "converting to RAML" should {
      val raml = volume.toRaml[AppVolume]
      behave like convertToProtobufThenToRAML(volume, raml)
      "convert all fields to RAML" in {
        raml shouldBe a[AppHostVolume]
        val ramlDocker = raml.asInstanceOf[AppHostVolume]
        ramlDocker.containerPath should be(mount.mountPath)
        ramlDocker.hostPath should be(hostVolume.hostPath)
        ramlDocker.mode should be(ReadMode.Rw)
      }
    }
  }

  "RAML docker volume conversion" when {
    val volume = AppHostVolume(containerPath = "/container", hostPath = "/host", mode = ReadMode.Rw)
    "converting to core HostVolume" should {
      val (hostVolume, mount) = Some(volume.fromRaml).collect {
        case state.VolumeWithMount(v: state.HostVolume, m) => (v, m)
      }.getOrElse(fail("expected docker volume"))

      "convert all fields from RAML to core" in {
        mount.mountPath should be(volume.containerPath)
        hostVolume.hostPath should be(volume.hostPath)
        mount.readOnly should be(false)
      }
    }
  }

  "core ExternalVolume conversion" when {
    val external = state.ExternalVolumeInfo(Some(123L), "external", "foo", Map("foo" -> "bla"))
    val externalVolume = state.ExternalVolume(None, external)
    val mount = state.VolumeMount(None, "/container")
    val volume = state.VolumeWithMount(externalVolume, mount)
    "converting to RAML" should {
      val raml = volume.toRaml[AppVolume]
      behave like convertToProtobufThenToRAML(volume, raml)
      "convert all fields to RAML" in {
        raml shouldBe a[AppExternalVolume]
        val externalRaml = raml.asInstanceOf[AppExternalVolume]
        externalRaml.containerPath should be(mount.mountPath)
        externalRaml.mode should be(ReadMode.Rw)
        externalRaml.external.name should be(Some(external.name))
        externalRaml.external.options should be(external.options)
        externalRaml.external.provider should be(Some(external.provider))
        externalRaml.external.size should be(external.size)
      }
    }
  }

  "RAML external volume conversion" when {
    val volume = AppExternalVolume(
      "/container",
      ExternalVolumeInfo(Some(1L), Some("vol-name"), Some("provider"), Map("foo" -> "bla")), ReadMode.Rw)
    "converting to core ExternalVolume" should {
      val (externalVolume, mount) = Some(volume.fromRaml).collect {
        case state.VolumeWithMount(v: state.ExternalVolume, m) => (v, m)
      }.getOrElse(fail("expected ExternalVolume"))
      "covert all fields from RAML to core" in {
        mount.mountPath should be(volume.containerPath)
        mount.readOnly should be(false)
        externalVolume.external.name should be(volume.external.name.head)
        externalVolume.external.provider should be(volume.external.provider.head)
        externalVolume.external.size should be(volume.external.size)
        externalVolume.external.options should be(volume.external.options)
      }
    }
  }

  "core PersistentVolume conversion" when {
    val persistent = state.PersistentVolumeInfo(123L, Some(1234L), state.DiskType.Path, Some("ssd-fast"))
    val persistentVolume = state.PersistentVolume(None, persistent)
    val mount = state.VolumeMount(None, "/container")
    val volume = state.VolumeWithMount(persistentVolume, mount)
    "converting to RAML" should {
      val raml = volume.toRaml[AppVolume]
      behave like convertToProtobufThenToRAML(volume, raml)
      "convert all fields to RAML" in {
        raml shouldBe a[AppPersistentVolume]
        val persistentRaml = raml.asInstanceOf[AppPersistentVolume]
        persistentRaml.containerPath should be(mount.mountPath)
        persistentRaml.mode should be(ReadMode.Rw)
        persistentRaml.persistent.`type` should be(Some(PersistentVolumeType.Path))
        persistentRaml.persistent.size should be(persistent.size)
        persistentRaml.persistent.maxSize should be(persistent.maxSize)
        persistentRaml.persistent.profileName should be(persistent.profileName)
        persistentRaml.persistent.constraints should be(empty)
      }
    }
  }

  "RAML persistent volume conversion" when {
    val volume = AppPersistentVolume(
      "/container",
      PersistentVolumeInfo(None, size = 123L, maxSize = Some(1234L), profileName = Some("ssd-fast"),
        constraints = Set.empty),
      ReadMode.Rw)
    "converting from RAML" should {
      val (persistent, mount) = Some(volume.fromRaml).collect {
        case state.VolumeWithMount(v: state.PersistentVolume, m) => (v, m)
      }.getOrElse(fail("expected PersistentVolume"))
      "convert all fields to core" in {
        mount.mountPath should be(volume.containerPath)
        mount.readOnly should be(false)
        persistent.persistent.`type` should be(state.DiskType.Root)
        persistent.persistent.size should be(volume.persistent.size)
        persistent.persistent.maxSize should be(volume.persistent.maxSize)
        persistent.persistent.profileName should be(volume.persistent.profileName)
        persistent.persistent.constraints should be(Set.empty)
      }
    }
  }

  "RAML persistent volume conversion for pods" when {

    "converting PersistentVolume from RAML" should {
      val ramlVolume = PodPersistentVolume(
        "/container",
        PersistentVolumeInfo(None, size = 123L, maxSize = Some(1234L), profileName = Some("ssd-fast"),
          constraints = Set.empty))
      val persistentVolume = Some(ramlVolume.asInstanceOf[PodVolume].fromRaml)
        .collect { case pv @ state.PersistentVolume(_, _) => pv }
        .getOrElse(fail("expected PersistentVolume"))
      "convert all fields to core" in {
        persistentVolume.name should be(Some(ramlVolume.name))
        val info = persistentVolume.persistent
        info.`type` should be(state.DiskType.Root)
        info.size should be(ramlVolume.persistent.size)
        info.maxSize should be(ramlVolume.persistent.maxSize)
        info.profileName should be(ramlVolume.persistent.profileName)
        info.constraints should be(Set.empty)
      }
    }

    "converting EphemeralVolume from RAML" should {
      val ramlVolume = PodEphemeralVolume("/container")
      val ephemeralVolume = Some(ramlVolume.asInstanceOf[PodVolume].fromRaml)
        .collect { case ev @ state.EphemeralVolume(_) => ev }
        .getOrElse(fail("expected EphemeralVolume"))

      "convert all fields to core" in {
        ephemeralVolume.name should be(Some(ramlVolume.name))
      }
    }

    "converting HostVolume from RAML" should {
      val ramlVolume = PodHostVolume("/container", "/path")
      val hostVolume = Some(ramlVolume.asInstanceOf[PodVolume].fromRaml)
        .collect { case hv @ state.HostVolume(_, _) => hv }
        .getOrElse(fail("expected HostVolume"))

      "convert all fields to core" in {
        hostVolume.name should be(Some(ramlVolume.name))
        hostVolume.hostPath should be(ramlVolume.host)
      }
    }

    "converting SecretVolume from RAML" should {
      val ramlVolume = PodSecretVolume("/container", "secret")
      val hostVolume = Some(ramlVolume.asInstanceOf[PodVolume].fromRaml)
        .collect { case hv @ state.SecretVolume(_, _) => hv }
        .getOrElse(fail("expected SecretVolume"))

      "convert all fields to core" in {
        hostVolume.name should be(Some(ramlVolume.name))
        hostVolume.secret should be(ramlVolume.secret)
      }
    }
  }
}
