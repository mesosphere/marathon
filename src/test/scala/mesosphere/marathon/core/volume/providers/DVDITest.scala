package mesosphere.marathon.core.volume.providers

import com.wix.accord._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state._
import org.apache.mesos.Protos.Volume.Mode
import org.scalatest.Matchers

sealed trait TCHelpers {
  val PVI = PersistentVolumeInfo.apply _
  val PV = PersistentVolume.apply _
}

class DVDIProvider_VolumeValidationTest extends MarathonSpec with Matchers with TCHelpers {
  case class TC(volumes: Iterable[PersistentVolume], wantsValid: Boolean)
  // validation concerns are split at different levels:
  // - between state/Volume and providers/*
  //     > containerPath, in particular, in enforced in state/Volume and not at the
  //       provider-level
  // - between validateVolume, validateApp, validateGroup
  val ttValidateVolume = Array[TC](
    TC(
      // various combinations of INVALID dvdi persistent volume parameters
      Set[PersistentVolume](
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverNam" -> "bar", "dvdi/volumetype" -> "io1")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/volumetype" -> "io1 ")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/newfstype" -> " xfs")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/newfstype" -> "")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/iops" -> "0")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/iops" -> "b")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/overwritefs" -> "b")), Mode.RO),
        PV("", PVI(None, None, None, Map.empty[String, String]), Mode.RO),
        PV("", PVI(None, Some("f"), None, Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("qaz"), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map("dvdi/driverName" -> "")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map("driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, Some("f"), Some(""), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, Some(""), Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, None, Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, None, None, Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, None, Some("dvdi"), Map.empty[String, String]), Mode.RO),
        PV("", PVI(None, Some("f"), None, Map.empty[String, String]), Mode.RO),
        PV("", PVI(Some(1L), None, None, Map.empty[String, String]), Mode.RO)
      ), false
    ),
    TC(
      // various combinations of VALID dvdi persistent volume parameters
      Set[PersistentVolume](
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/volumetype" -> "io1")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/newfstype" -> "xfs")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/iops" -> "1")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/overwritefs" -> "true")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/overwritefs" -> "false")), Mode.RO),
        PV("", PVI(Some(1L), Some("f"), Some("dvdi"), Map("dvdi/driverName" -> "bar", "a" -> "b")), Mode.RO),
        PV("", PVI(Some(1L), Some("f"), Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO)
      ), true
    )
  )
  test("validPersistentVolume") {
    for (tc <- ttValidateVolume; v <- tc.volumes) {
      val result = validate(v)(DVDIProvider.validPersistentVolume)
      assert(result.isSuccess == tc.wantsValid,
        s"expected ${tc.wantsValid} instead of $result for volume $v")
    }
  }
}

//def volumeToEnv(v: PersistentVolume, i: Iterable[Environment.Variable]): Iterable[Environment.Variable]
class DVDIProvider_VolumeToEnvTest extends MarathonSpec with Matchers with TCHelpers {
  import org.apache.mesos.Protos.Environment
  case class TC(pv: PersistentVolume, env: Seq[Environment.Variable], wantsEnv: Seq[Environment.Variable])

  def mkVar(name: String, value: String): Environment.Variable =
    Environment.Variable.newBuilder.setName(name).setValue(value).build

  val ttVolumeToEnv = Array[TC](
    TC(
      PV("/path", PVI(None, Some("foo"), Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
      Seq[Environment.Variable](),
      Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/path"),
        mkVar("DVDI_VOLUME_NAME", "foo"),
        mkVar("DVDI_VOLUME_DRIVER", "bar")
      )
    ),
    TC(
      PV("/path", PVI(Some(1L), Some("foo"), Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
      Seq[Environment.Variable](),
      Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/path"),
        mkVar("DVDI_VOLUME_NAME", "foo"),
        mkVar("DVDI_VOLUME_DRIVER", "bar"),
        mkVar("DVDI_VOLUME_OPTS", "size=1")
      )
    ),
    TC(
      PV("/path", PVI(Some(1L), Some("foo"), Some("dvdi"), Map(
        "dvdi/driverName" -> "bar",
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
      PV("/path", PVI(None, Some("foo"), Some("dvdi"), Map(
        "dvdi/driverName" -> "bar",
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
      PV("/path", PVI(None, Some("foo"), Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
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
      PV("/path", PVI(None, Some("foo"), Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
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
      PV("/path", PVI(None, Some("foo"), Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
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
  test("volumeToEnv") {
    for (tc <- ttVolumeToEnv) {
      assertResult(tc.wantsEnv, "generated environment vars don't match expectations") {
        DVDIProvider.volumeToEnv(tc.pv, tc.env)
      }
    }
  }
}
