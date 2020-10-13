package mesosphere.marathon
package core.externalvolume.impl.providers

import scala.jdk.CollectionConverters._
import mesosphere.UnitTest
import mesosphere.marathon.state.{CSIExternalVolumeInfo, ExternalVolume, VolumeMount}
import org.apache.mesos.Protos.Volume.Source.{CSIVolume => MesosCSIVolume}
import org.apache.mesos

class CSIProviderVolumeToUnifiedMesosVolumeTest extends UnitTest {

  val mountPath = "/path"
  val readOnly = true
  val mount = VolumeMount(None, mountPath, readOnly)

  "properly converts CSI parameters" in {
    val volInfo = Builders.newCSIExternalVolumeInfo(
      accessMode = CSIExternalVolumeInfo.AccessMode.SINGLE_NODE_WRITER,
      nodePublishSecret = Map("key" -> "publish-secret-key"),
      nodeStageSecret = Map("key" -> "stage-secret-key"),
      volumeContext = Map("volume" -> "context")
    )

    val extVol = ExternalVolume(None, volInfo)

    val vol = CSIProvider.Builders.toUnifiedContainerVolume(extVol, mount)

    val csiProto = vol.getSource.getCsiVolume
    csiProto.getPluginName shouldBe volInfo.pluginName
    csiProto.getStaticProvisioning.getVolumeId shouldBe volInfo.name

    val capability = csiProto.getStaticProvisioning.getVolumeCapability
    capability.getAccessMode.getMode shouldBe MesosCSIVolume.VolumeCapability.AccessMode.Mode.SINGLE_NODE_WRITER

    val stageSecrets = csiProto.getStaticProvisioning.getNodeStageSecretsMap.asScala.toMap
    stageSecrets.keySet shouldBe Set("key")
    stageSecrets("key").getReference.getName shouldBe ("stage-secret-key")

    val volumeContext = csiProto.getStaticProvisioning.getVolumeContextMap.asScala
    volumeContext("volume") shouldBe ("context")

    val publishSecrets = csiProto.getStaticProvisioning.getNodePublishSecretsMap.asScala.toMap
    publishSecrets.keySet shouldBe Set("key")
    publishSecrets("key").getReference.getName shouldBe ("publish-secret-key")

    csiProto.getStaticProvisioning.getVolumeContextMap.asScala.toMap shouldBe volInfo.volumeContext
    vol.getSource.getType shouldBe mesos.Protos.Volume.Source.Type.CSI_VOLUME
  }
}
