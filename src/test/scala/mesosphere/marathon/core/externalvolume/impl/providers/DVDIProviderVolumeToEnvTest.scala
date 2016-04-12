package mesosphere.marathon.core.externalvolume.impl.providers

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.{ ExternalVolumeInfo, ExternalVolume }
import org.apache.mesos.Protos.Volume.Mode
import org.scalatest.Matchers

class DVDIProviderVolumeToEnvTest extends MarathonSpec with Matchers {
  import org.apache.mesos.Protos.Environment
  case class TestParameters(
    externalVolume: ExternalVolume,
    otherEnv: Seq[Environment.Variable],
    wantsEnv: Seq[Environment.Variable])

  def mkVar(name: String, value: String): Environment.Variable =
    Environment.Variable.newBuilder.setName(name).setValue(value).build

  val testParameters = Seq[TestParameters](
    TestParameters(
      ExternalVolume("/path", ExternalVolumeInfo(None, "foo", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO),
      otherEnv = Seq[Environment.Variable](),
      wantsEnv = Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/path"),
        mkVar("DVDI_VOLUME_NAME", "foo"),
        mkVar("DVDI_VOLUME_DRIVER", "bar")
      )
    ),
    TestParameters(
      ExternalVolume("/path", ExternalVolumeInfo(Some(1L), "foo", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO),
      otherEnv = Seq[Environment.Variable](),
      wantsEnv = Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/path"),
        mkVar("DVDI_VOLUME_NAME", "foo"),
        mkVar("DVDI_VOLUME_DRIVER", "bar"),
        mkVar("DVDI_VOLUME_OPTS", "size=1")
      )
    ),
    TestParameters(
      ExternalVolume("/path", ExternalVolumeInfo(Some(1L), "foo", "dvdi", Map(
        "dvdi/driver" -> "bar",
        "dvdi/size" -> "2"
      )), Mode.RO),
      otherEnv = Seq[Environment.Variable](),
      wantsEnv = Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/path"),
        mkVar("DVDI_VOLUME_NAME", "foo"),
        mkVar("DVDI_VOLUME_DRIVER", "bar"),
        mkVar("DVDI_VOLUME_OPTS", "size=1")
      )
    ),
    TestParameters(
      ExternalVolume("/path", ExternalVolumeInfo(None, "foo", "dvdi", Map(
        "dvdi/driver" -> "bar",
        "dvdi/size" -> "abc"
      )), Mode.RO),
      otherEnv = Seq[Environment.Variable](),
      wantsEnv = Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/path"),
        mkVar("DVDI_VOLUME_NAME", "foo"),
        mkVar("DVDI_VOLUME_DRIVER", "bar"),
        mkVar("DVDI_VOLUME_OPTS", "size=abc")
      )
    ),
    TestParameters(
      ExternalVolume("/path", ExternalVolumeInfo(None, "foo", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO),
      otherEnv = Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH0", "/tmp"),
        mkVar("DVDI_VOLUME_NAME0", "qaz"),
        mkVar("DVDI_VOLUME_DRIVER0", "wsx")
      ),
      wantsEnv = Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH1", "/path"),
        mkVar("DVDI_VOLUME_NAME1", "foo"),
        mkVar("DVDI_VOLUME_DRIVER1", "bar")
      )
    ),
    TestParameters(
      ExternalVolume("/path", ExternalVolumeInfo(None, "foo", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO),
      otherEnv = Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/tmp"),
        mkVar("DVDI_VOLUME_NAME", "qaz"),
        mkVar("DVDI_VOLUME_DRIVER", "wsx")
      ),
      wantsEnv = Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH1", "/path"),
        mkVar("DVDI_VOLUME_NAME1", "foo"),
        mkVar("DVDI_VOLUME_DRIVER1", "bar")
      )
    ),
    TestParameters(
      ExternalVolume("/path", ExternalVolumeInfo(None, "foo", "dvdi", Map("dvdi/driver" -> "bar")), Mode.RO),
      otherEnv = Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH", "/tmp"),
        mkVar("DVDI_VOLUME_NAME", "qaz"),
        mkVar("DVDI_VOLUME_DRIVER", "wsx"),
        mkVar("DVDI_VOLUME_CONTAINERPATH1", "/var"),
        mkVar("DVDI_VOLUME_NAME1", "edc"),
        mkVar("DVDI_VOLUME_DRIVER1", "rfv")
      ),
      wantsEnv = Seq[Environment.Variable](
        mkVar("DVDI_VOLUME_CONTAINERPATH2", "/path"),
        mkVar("DVDI_VOLUME_NAME2", "foo"),
        mkVar("DVDI_VOLUME_DRIVER2", "bar")
      )
    ) // TestParameters
  )
  for ((testParams, idx) <- testParameters.zipWithIndex) {
    test(s"volumeToEnv $idx") {
      assertResult(testParams.wantsEnv, "generated environment vars don't match expectations") {
        DVDIProvider.volumeToEnv(testParams.externalVolume, testParams.otherEnv)
      }
    }
  }
}
