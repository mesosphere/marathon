package mesosphere.marathon
package state

import com.wix.accord._
import mesosphere.UnitTest
import mesosphere.marathon.api.serialization.VolumeSerializer
import mesosphere.marathon.api.v2.ValidationHelper
import org.apache.mesos.Protos.Resource.DiskInfo.Source
import org.apache.mesos.Protos.Volume.Mode

class VolumeTest extends UnitTest {
  import mesosphere.marathon.test.MarathonTestHelper.constraint

  def survivesProtobufSerializationRoundtrip(title: => String, volume: => Volume): Unit = {
    s"$title survives protobuf serialization round-trip" in {
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

  "Volume" should {

    behave like survivesProtobufSerializationRoundtrip("root vol, no constraints", persistent(Fixture.rootVolNoConstraints))
    behave like survivesProtobufSerializationRoundtrip("path vol w/ constraint", persistent(Fixture.pathVolWithConstraint))
    behave like survivesProtobufSerializationRoundtrip("mount vol w/ maxSize", persistent(Fixture.mountVolWithMaxSize))
    behave like survivesProtobufSerializationRoundtrip("ext vol w/o size", external(Fixture.extVolNoSize))
    behave like survivesProtobufSerializationRoundtrip("ext vol w/ size", external(Fixture.extVolWithSize))
    behave like survivesProtobufSerializationRoundtrip("host vol", Fixture.hostVol)

    "validating PersistentVolumeInfo constraints accepts an empty constraint list" in new Fixture {
      validate(rootVolNoConstraints).isSuccess shouldBe true
    }

    "validating PersistentVolumeInfo constraints rejects unsupported fields" in {
      val pvi = PersistentVolumeInfo(
        1024,
        `type` = DiskType.Path,
        constraints = Set(constraint("invalid", "LIKE", Some("regex"))))

      val result = validate(pvi)
      result.isSuccess shouldBe false
      ValidationHelper.getAllRuleConstrains(result).map(_.message) shouldBe Set("Unsupported field")
    }

    "validating PersistentVolumeInfo constraints rejected for root resources" in {
      val result = validate(
        PersistentVolumeInfo(
          1024,
          `type` = DiskType.Root,
          constraints = Set(constraint("path", "LIKE", Some("regex")))))
      result.isSuccess shouldBe false
      ValidationHelper.getAllRuleConstrains(result).map(_.message) shouldBe Set("Constraints on root volumes are not supported")
    }

    "validating PersistentVolumeInfo constraints rejects bad regex" in {
      val pvi = PersistentVolumeInfo(
        1024,
        `type` = DiskType.Path,
        constraints = Set(constraint("path", "LIKE", Some("(bad regex"))))
      val result = validate(pvi)
      result.isSuccess shouldBe false
      ValidationHelper.getAllRuleConstrains(result).map(_.message) shouldBe Set("Invalid regular expression")
    }

    "validating PersistentVolumeInfo accepts a valid constraint" in new Fixture {
      val result = validate(pathVolWithConstraint)
      result.isSuccess shouldBe true
    }

    "validating PersistentVolumeInfo maxSize parameter wrt type" in new Fixture {
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

    "validating that DiskSource asMesos converts to an Option Mesos Protobuffer" in {
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
}
