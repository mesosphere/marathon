package mesosphere.marathon.core.externalvolume.providers

import com.wix.accord._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.core.externalvolume.ExternalVolumeProvider
import mesosphere.marathon.state._
import org.apache.mesos.Protos.Volume.Mode
import org.scalatest.Matchers

sealed trait TCHelpers {
  val EVI = ExternalVolumeInfo.apply _
  val EV = ExternalVolume.apply _
}

class DVDIProvider_VolumeValidationTest extends MarathonSpec with Matchers with TCHelpers {
  case class TC(volumes: Iterable[ExternalVolume], wantsValid: Boolean)
  // validation concerns are split at different levels:
  // - between state/Volume and providers/*
  //     > containerPath, in particular, in enforced in state/Volume and not at the
  //       provider-level
  // - between validateVolume, validateApp, validateGroup
  val ttValidateVolume = Seq[TC](
    TC(
      // various combinations of INVALID external persistent volume parameters
      Set[ExternalVolume](
        EV("", EVI(None, "f", "dvdi", Map(
          "dvdi/driverNam" -> "rexray", "dvdi/volumetype" -> "io1")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map(
          "dvdi/driver" -> "rexray", "dvdi/volumetype" -> "io1 ")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map(
          "dvdi/driver" -> "rexray", "dvdi/newfstype" -> " xfs")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map(
          "dvdi/driver" -> "rexray", "dvdi/newfstype" -> "")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map(
          "dvdi/driver" -> "rexray", "dvdi/iops" -> "0")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map(
          "dvdi/driver" -> "rexray", "dvdi/iops" -> "b")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map(
          "dvdi/driver" -> "rexray", "dvdi/overwritefs" -> "b")), Mode.RO),

        EV("", EVI(None, "f", "qaz", Map("dvdi/driver" -> "bar")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map("dvdi/driver" -> "")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map("driver" -> "bar")), Mode.RO),
        EV("", EVI(None, "f", "", Map("dvdi/driver" -> "bar")), Mode.RO),
        EV("", EVI(None, "", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO),
        EV("", EVI(None, "f", "", Map.empty[String, String]), Mode.RO),
        EV("", EVI(Some(1L), "", "", Map.empty[String, String]), Mode.RO)
      ), false
    ),
    TC(
      // various combinations of VALID external persistent volume parameters
      Set[ExternalVolume](
        EV("", EVI(None, "f", "dvdi", Map(
          "dvdi/driver" -> "bar", "dvdi/volumetype" -> "io1")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map(
          "dvdi/driver" -> "bar", "dvdi/newfstype" -> "xfs")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map(
          "dvdi/driver" -> "bar", "dvdi/iops" -> "1")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map(
          "dvdi/driver" -> "bar", "dvdi/overwritefs" -> "true")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map(
          "dvdi/driver" -> "bar", "dvdi/overwritefs" -> "false")), Mode.RO),
        EV("", EVI(Some(1L), "f", "dvdi", Map("dvdi/driver" -> "bar", "a" -> "b")), Mode.RO),
        EV("", EVI(Some(1L), "f", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO),
        EV("", EVI(None, "f", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO)
      ), true
    )
  )
  for ((tc, idx) <- ttValidateVolume.zipWithIndex; (v, vidx) <- tc.volumes.zipWithIndex) {
    test(s"validExternalVolume $idx,$vidx") {
      val result = validate(v)(DVDIProvider.volumeValidation)
      assert(result.isSuccess == tc.wantsValid,
        s"expected ${tc.wantsValid} instead of $result for volume $v")
    }
  }
}

//def volumeToEnv(v: ExternalVolume, i: Iterable[Environment.Variable]): Iterable[Environment.Variable]
class DVDIProvider_VolumeToEnvTest extends MarathonSpec with Matchers with TCHelpers {
  import org.apache.mesos.Protos.Environment
  case class TC(pv: ExternalVolume, env: Seq[Environment.Variable], wantsEnv: Seq[Environment.Variable])

  def mkVar(name: String, value: String): Environment.Variable =
    Environment.Variable.newBuilder.setName(name).setValue(value).build

  val ttVolumeToEnv = Seq[TC](
    TC(
      EV("/path", EVI(None, "foo", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO),
      Seq[Environment.Variable](),
      Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/path"),
        mkVar("DVDI_VOLUME_NAME", "foo"),
        mkVar("DVDI_VOLUME_DRIVER", "bar")
      )
    ),
    TC(
      EV("/path", EVI(Some(1L), "foo", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO),
      Seq[Environment.Variable](),
      Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/path"),
        mkVar("DVDI_VOLUME_NAME", "foo"),
        mkVar("DVDI_VOLUME_DRIVER", "bar"),
        mkVar("DVDI_VOLUME_OPTS", "size=1")
      )
    ),
    TC(
      EV("/path", EVI(Some(1L), "foo", "dvdi", Map(
        "dvdi/driver" -> "bar",
        "dvdi/size" -> "2"
      )), Mode.RO),
      Seq[Environment.Variable](),
      Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/path"),
        mkVar("DVDI_VOLUME_NAME", "foo"),
        mkVar("DVDI_VOLUME_DRIVER", "bar"),
        mkVar("DVDI_VOLUME_OPTS", "size=1")
      )
    ),
    TC(
      EV("/path", EVI(None, "foo", "dvdi", Map(
        "dvdi/driver" -> "bar",
        "dvdi/size" -> "abc"
      )), Mode.RO),
      Seq[Environment.Variable](),
      Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/path"),
        mkVar("DVDI_VOLUME_NAME", "foo"),
        mkVar("DVDI_VOLUME_DRIVER", "bar"),
        mkVar("DVDI_VOLUME_OPTS", "size=abc")
      )
    ),
    TC(
      EV("/path", EVI(None, "foo", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO),
      Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH0", "/tmp"),
        mkVar("DVDI_VOLUME_NAME0", "qaz"),
        mkVar("DVDI_VOLUME_DRIVER0", "wsx")
      ),
      Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH1", "/path"),
        mkVar("DVDI_VOLUME_NAME1", "foo"),
        mkVar("DVDI_VOLUME_DRIVER1", "bar")
      )
    ),
    TC(
      EV("/path", EVI(None, "foo", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO),
      Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/tmp"),
        mkVar("DVDI_VOLUME_NAME", "qaz"),
        mkVar("DVDI_VOLUME_DRIVER", "wsx")
      ),
      Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH1", "/path"),
        mkVar("DVDI_VOLUME_NAME1", "foo"),
        mkVar("DVDI_VOLUME_DRIVER1", "bar")
      )
    ),
    TC(
      EV("/path", EVI(None, "foo", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO),
      Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/tmp"),
        mkVar("DVDI_VOLUME_NAME", "qaz"),
        mkVar("DVDI_VOLUME_DRIVER", "wsx"),
        mkVar("DVDI_VOLUME_CONTAINERPATH1", "/var"),
        mkVar("DVDI_VOLUME_NAME1", "edc"),
        mkVar("DVDI_VOLUME_DRIVER1", "rfv")
      ),
      Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH2", "/path"),
        mkVar("DVDI_VOLUME_NAME2", "foo"),
        mkVar("DVDI_VOLUME_DRIVER2", "bar")
      )
    ) // TC
  )
  for ((tc, idx) <- ttVolumeToEnv.zipWithIndex) {
    test(s"volumeToEnv $idx") {
      assertResult(tc.wantsEnv, "generated environment vars don't match expectations") {
        DVDIProvider.volumeToEnv(tc.pv, tc.env)
      }
    }
  }
}
