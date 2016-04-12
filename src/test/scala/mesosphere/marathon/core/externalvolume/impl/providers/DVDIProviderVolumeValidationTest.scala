package mesosphere.marathon.core.externalvolume.impl.providers

import com.wix.accord._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state._
import org.apache.mesos.Protos.Volume.Mode
import org.scalatest.Matchers

class DVDIProviderVolumeValidationTest extends MarathonSpec with Matchers {
  case class TestParameters(volumes: Iterable[ExternalVolume], wantsValid: Boolean)
  // validation concerns are split at different levels:
  // - between state/Volume and providers/*
  //     > containerPath, in particular, in enforced in state/Volume and not at the
  //       provider-level
  // - between validateVolume, validateApp, validateGroup
  val ttValidateVolume = Seq[TestParameters](
    TestParameters(
      // various combinations of INVALID external persistent volume parameters
      Seq[ExternalVolume](
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driverNam" -> "rexray", "dvdi/volumetype" -> "io1")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "rexray", "dvdi/volumetype" -> "io1 ")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "rexray", "dvdi/newfstype" -> " xfs")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "rexray", "dvdi/newfstype" -> "")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "rexray", "dvdi/iops" -> "0")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "rexray", "dvdi/iops" -> "b")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "rexray", "dvdi/overwritefs" -> "b")
          ),
          mode = Mode.RO
        ),

        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "qaz",
            options = Map("dvdi/driver" -> "bar")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("driver" -> "bar")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "",
            options = Map("dvdi/driver" -> "bar"
            )
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar"
            )
          ),
          mode = Mode.
            RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "",
            options = Map.empty[String, String]
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = Some(1L), name = "", provider = "",
            options = Map.empty[String, String]
          ),
          mode = Mode.RO)
      ), wantsValid = false
    ),
    TestParameters(
      // various combinations of VALID external persistent volume parameters
      Seq[ExternalVolume](
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar", "dvdi/volumetype" -> "io1")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar", "dvdi/newfstype" -> "xfs")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar", "dvdi/iops" -> "1")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar", "dvdi/overwritefs" -> "true")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar", "dvdi/overwritefs" -> "false")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = Some(1L), name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar", "a" -> "b")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = Some(1L), name = "f", provider = "dvdi", options = Map("dvdi/driver" -> "bar")
          ),
          mode = Mode.RO
        ),
        ExternalVolume(
          containerPath = "",
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar"
            )
          ),
          mode = Mode.RO
        )
      ), wantsValid = true
    )
  )
  for ((testParams, idx) <- ttValidateVolume.zipWithIndex; (v, vidx) <- testParams.volumes.zipWithIndex) {
    test(s"validExternalVolume $idx,$vidx") {
      val result = validate(v)(DVDIProvider.validations.volume)
      assert(result.isSuccess == testParams.wantsValid,
        s"test case $idx/$vidx expected ${testParams.wantsValid} instead of $result for volume $v")
    }
  }
}

