package mesosphere.marathon
package core.externalvolume.impl.providers

import mesosphere.UnitTest
import mesosphere.marathon.state.{ExternalVolume, DVDIExternalVolumeInfo, VolumeMount}
import org.apache.mesos.Protos.Volume
import mesosphere.marathon.test.MesosProtoBuilders

class DVDIProviderVolumeToUnifiedMesosVolumeTest extends UnitTest {

  case class TestParameters(externalVolume: ExternalVolume, volumeMount: VolumeMount, wantsVol: Volume)

  val mountPath = "/path"
  val readOnly = true
  import MesosProtoBuilders.{newVolume, newVolumeSource}

  val testParameters = Seq[TestParameters](
    TestParameters(
      ExternalVolume(None, DVDIExternalVolumeInfo(None, "foo", "dvdi", Map("dvdi/driver" -> "bar"))),
      VolumeMount(None, mountPath, readOnly),
      newVolume(containerPath = "/path", mode = Volume.Mode.RO, source = newVolumeSource.docker(driver = "bar", name = "foo"))
    ),
    TestParameters(
      ExternalVolume(None, DVDIExternalVolumeInfo(Some(1L), "foo", "dvdi", Map("dvdi/driver" -> "bar"))),
      VolumeMount(None, mountPath, readOnly),
      newVolume(
        containerPath = "/path",
        mode = Volume.Mode.RO,
        source = newVolumeSource.docker(driver = "bar", name = "foo", options = Map("size" -> "1"))
      )
    ),
    TestParameters(
      ExternalVolume(None, DVDIExternalVolumeInfo(Some(1L), "foo", "dvdi", Map("dvdi/driver" -> "bar", "dvdi/size" -> "2"))),
      VolumeMount(None, mountPath, readOnly),
      newVolume(
        containerPath = "/path",
        mode = Volume.Mode.RO,
        source = newVolumeSource.docker(driver = "bar", name = "foo", options = Map("size" -> "1"))
      )
    ),
    TestParameters(
      ExternalVolume(
        None,
        DVDIExternalVolumeInfo(
          None,
          "foo",
          "dvdi",
          Map(
            "dvdi/driver" -> "bar",
            "dvdi/size" -> "abc"
          )
        )
      ),
      VolumeMount(None, mountPath, readOnly),
      newVolume(
        containerPath = "/path",
        mode = Volume.Mode.RO,
        source = newVolumeSource.docker(driver = "bar", name = "foo", options = Map("size" -> "abc"))
      )
    ) // TestParameters
  )

  "DVDIProviderVolumeToUnifiedMesosVolume" should {
    for ((testParams, idx) <- testParameters.zipWithIndex) {
      s"toUnifiedMesosVolume $idx" in {
        assertResult(testParams.wantsVol, "generated volume doesn't match expectations") {
          DVDIProvider.Builders.toUnifiedContainerVolume(testParams.externalVolume, testParams.volumeMount)
        }
      }
    }
  }
}
