package mesosphere.marathon
package core.externalvolume.impl.providers

import com.wix.accord._
import mesosphere.UnitTest
import mesosphere.marathon.state._

class DVDIProviderVolumeValidationTest extends UnitTest {
  case class TestParameters(volumes: Seq[ExternalVolume], wantsValid: Boolean)
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
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driverNam" -> "rexray", "dvdi/volumetype" -> "io1")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "rexray", "dvdi/volumetype" -> "io1 ")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "rexray", "dvdi/newfstype" -> " xfs")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "rexray", "dvdi/newfstype" -> "")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "rexray", "dvdi/iops" -> "0")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "rexray", "dvdi/iops" -> "b")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "rexray", "dvdi/overwritefs" -> "b")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "qaz",
            options = Map("dvdi/driver" -> "bar")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("driver" -> "bar")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "",
            options = Map("dvdi/driver" -> "bar"
            )
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar"
            )
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "",
            options = Map.empty[String, String]
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = Some(1L), name = "", provider = "",
            options = Map.empty[String, String]
          )
        )
      ),
      wantsValid = false
    ),
    TestParameters(
      // various combinations of VALID external persistent volume parameters
      Seq[ExternalVolume](
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar", "dvdi/volumetype" -> "io1")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar", "dvdi/newfstype" -> "xfs")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar", "dvdi/iops" -> "1")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar", "dvdi/overwritefs" -> "true")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar", "dvdi/overwritefs" -> "false")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = Some(1L), name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar", "a" -> "b")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = Some(1L), name = "f", provider = "dvdi", options = Map("dvdi/driver" -> "bar")
          )
        ),
        ExternalVolume(
          name = None,
          external = ExternalVolumeInfo(
            size = None, name = "f", provider = "dvdi",
            options = Map("dvdi/driver" -> "bar"
            )
          )
        )
      ),
      wantsValid = true
    )
  )

  "DVDIProviderVolumeValidation" should {
    for ((testParams, idx) <- ttValidateVolume.zipWithIndex; (v, vidx) <- testParams.volumes.zipWithIndex) {
      s"validExternalVolume $idx,$vidx" in {
        val result = validate(v)(DVDIProvider.validations.volume)
        assert(
          result.isSuccess == testParams.wantsValid,
          s"test case $idx/$vidx expected ${testParams.wantsValid} instead of $result for volume $v")
      }
    }
  }
}
