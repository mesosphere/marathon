package mesosphere.marathon.state

import com.wix.accord._
import mesosphere.marathon.api.serialization.VolumeSerializer
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.test.MarathonSpec
import org.scalatest.Matchers
import org.apache.mesos.Protos.Resource.DiskInfo.Source
import org.apache.mesos.Protos.Volume.Mode

class VolumeTest extends MarathonSpec with Matchers {
  import mesosphere.marathon.test.MarathonTestHelper.constraint

  def survivesProtobufSerializationRoundtrip(title: => String, volume: => Volume): Unit = {
    test(s"$title survives protobuf serialization round-trip") {
      val protobuf = VolumeSerializer.toProto(volume)
      val resurrected = Volume(protobuf)
      resurrected should be(volume)
    }
  }

  def persistent(info: PersistentVolumeInfo, containerPath: String = "cpath", mode: Mode = Mode.RW): PersistentVolume =
    PersistentVolume(
      containerPath = containerPath,
      persistent = info,
      mode = mode
    )

  def external(info: ExternalVolumeInfo, containerPath: String = "cpath", mode: Mode = Mode.RW): ExternalVolume =
    ExternalVolume(
      containerPath = containerPath,
      external = info,
      mode = mode
    )

  trait Fixture {
    val rootVolNoConstraints = PersistentVolumeInfo(
      1024,
      constraints = Set.empty)
    val pathVolWithConstraint = PersistentVolumeInfo(
      1024,
      `type` = DiskType.Path,
      constraints = Set(constraint("path", "LIKE", Some("valid regex"))))
    val mountVolWithMaxSize = PersistentVolumeInfo(
      1024,
      `type` = DiskType.Mount,
      maxSize = Some(2048))
    val extVolNoSize = ExternalVolumeInfo(
      name = "volname",
      provider = "provider",
      options = Map("foo" -> "bar")
    )
    val extVolWithSize = ExternalVolumeInfo(
      size = Option(1),
      name = "volname",
      provider = "provider",
      options = Map("foo" -> "bar", "baz" -> "qaw")
    )
    val hostVol = DockerVolume(
      containerPath = "cpath",
      hostPath = "/host/path",
      mode = Mode.RW
    )
  }
  object Fixture extends Fixture

  survivesProtobufSerializationRoundtrip("root vol, no constraints", persistent(Fixture.rootVolNoConstraints))
  survivesProtobufSerializationRoundtrip("path vol w/ constraint", persistent(Fixture.pathVolWithConstraint))
  survivesProtobufSerializationRoundtrip("mount vol w/ maxSize", persistent(Fixture.mountVolWithMaxSize))
  survivesProtobufSerializationRoundtrip("ext vol w/o size", external(Fixture.extVolNoSize))
  survivesProtobufSerializationRoundtrip("ext vol w/ size", external(Fixture.extVolWithSize))
  survivesProtobufSerializationRoundtrip("host vol", Fixture.hostVol)

  test("validating PersistentVolumeInfo constraints accepts an empty constraint list") {
    new Fixture {
      validate(rootVolNoConstraints).isSuccess shouldBe true
    }
  }

  test("validating PersistentVolumeInfo constraints rejects unsupported fields") {
    val pvi = PersistentVolumeInfo(
      1024,
      `type` = DiskType.Path,
      constraints = Set(constraint("invalid", "LIKE", Some("regex"))))

    val result = validate(pvi)
    result.isSuccess shouldBe false
    ValidationHelper.getAllRuleConstrains(result).map(_.message) shouldBe Set("Unsupported field")
  }

  test("validating PersistentVolumeInfo constraints rejected for root resources") {
    val result = validate(
      PersistentVolumeInfo(
        1024,
        `type` = DiskType.Root,
        constraints = Set(constraint("path", "LIKE", Some("regex")))))
    result.isSuccess shouldBe false
    ValidationHelper.getAllRuleConstrains(result).map(_.message) shouldBe Set("Constraints on root volumes are not supported")
  }

  test("validating PersistentVolumeInfo constraints rejects bad regex") {
    val pvi = PersistentVolumeInfo(
      1024,
      `type` = DiskType.Path,
      constraints = Set(constraint("path", "LIKE", Some("(bad regex"))))
    val result = validate(pvi)
    result.isSuccess shouldBe false
    ValidationHelper.getAllRuleConstrains(result).map(_.message) shouldBe Set("Invalid regular expression")
  }

  test("validating PersistentVolumeInfo accepts a valid constraint") {
    val pvi = PersistentVolumeInfo(
      1024,
      `type` = DiskType.Path,
      constraints = Set(constraint("path", "LIKE", Some("valid regex"))))
    val result = validate(pvi)
    result.isSuccess shouldBe true
  }

  test("validating PersistentVolumeInfo maxSize parameter wrt type") {
    new Fixture {
      val resultRoot = validate(
        PersistentVolumeInfo(1024, `type` = DiskType.Root, maxSize = Some(2048)))
      resultRoot.isSuccess shouldBe false
      ValidationHelper.getAllRuleConstrains(resultRoot).map(_.message) shouldBe Set("Only mount volumes can have maxSize")

      val resultPath = validate(
        PersistentVolumeInfo(1024, `type` = DiskType.Path, maxSize = Some(2048)))
      resultPath.isSuccess shouldBe false
      ValidationHelper.getAllRuleConstrains(resultPath).map(_.message) shouldBe Set("Only mount volumes can have maxSize")

      validate(mountVolWithMaxSize).isSuccess shouldBe true
    }
  }

  test("validating that DiskSource asMesos converts to an Option Mesos Protobuffer") {
    DiskSource(DiskType.Root, None).asMesos shouldBe None
    val Some(pathDisk) = DiskSource(DiskType.Path, Some("/path/to/folder")).asMesos
    pathDisk.getPath.getRoot shouldBe "/path/to/folder"
    pathDisk.getType shouldBe Source.Type.PATH

    val Some(mountDisk) = DiskSource(DiskType.Mount, Some("/path/to/mount")).asMesos
    mountDisk.getMount.getRoot shouldBe "/path/to/mount"
    mountDisk.getType shouldBe Source.Type.MOUNT

    a[IllegalArgumentException] shouldBe thrownBy {
      DiskSource(DiskType.Root, Some("/path")).asMesos
    }
    a[IllegalArgumentException] shouldBe thrownBy {
      DiskSource(DiskType.Path, None).asMesos
    }
    a[IllegalArgumentException] shouldBe thrownBy {
      DiskSource(DiskType.Mount, None).asMesos
    }
  }

}
