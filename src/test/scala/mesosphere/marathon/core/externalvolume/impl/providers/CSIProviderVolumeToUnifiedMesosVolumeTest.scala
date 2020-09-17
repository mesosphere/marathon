package mesosphere.marathon.core.externalvolume.impl.providers

import mesosphere.UnitTest
import mesosphere.marathon.Builders
import mesosphere.marathon.state.{ExternalVolume, VolumeMount}

class CSIProviderVolumeToUnifiedMesosVolumeTest extends UnitTest {

  val mountPath = "/path"
  val readOnly = true
  val mount = VolumeMount(None, mountPath, readOnly)

  "properly converts CSI paramters" in {
    val extVol = ExternalVolume(None, Builders.newCSIExternalVolumeInfo())
    val vol = CSIProvider.Builders.toUnifiedContainerVolume(extVol, mount)

    println(vol.getSource)
  }
}
