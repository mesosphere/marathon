package mesosphere.marathon.core.externalvolume.impl.providers

import scala.jdk.CollectionConverters._
import com.wix.accord._
import mesosphere.marathon.api.serialization.{ExternalVolumeInfoSerializer, SecretSerializer}
import mesosphere.marathon.core.externalvolume.impl.{ExternalVolumeProvider, ExternalVolumeValidations}
import mesosphere.marathon.raml.{App, AppExternalVolume, Container => AppContainer}
import mesosphere.marathon.state._
import org.apache.mesos.Protos.{Volume => MesosVolume}

/**
  * CSIProvider handles external volumes allocated
  * by a Mesos CSI support.
  */
private[externalvolume] case object CSIProvider extends ExternalVolumeProvider {
  override val name: String = "csi"

  override def validations: ExternalVolumeValidations = CSIProviderValidations
  import MesosVolume.Source.CSIVolume

  object Builders {
    def toUnifiedContainerVolume(volume: ExternalVolume, mount: VolumeMount): MesosVolume = {
      volume.external match {
        case _: GenericExternalVolumeInfo =>
          throw new IllegalStateException("Bug: DVDIProviderValidations should be used for GenericExternalVolumeInfo")
        case info: CSIExternalVolumeInfo =>
          val staticProvisioning = CSIVolume.StaticProvisioning
            .newBuilder()
            .setVolumeId(info.name)
            .setReadonly(mount.readOnly)
            .setVolumeCapability(ExternalVolumeInfoSerializer.toProtoVolumeCapability(info))
            .putAllNodeStageSecrets(info.nodeStageSecret.view.mapValues(SecretSerializer.toSecretReference).toMap.asJava)
            .putAllNodePublishSecrets(info.nodePublishSecret.view.mapValues(SecretSerializer.toSecretReference).toMap.asJava)
            .putAllVolumeContext(info.volumeContext.asJava)

          val volBuilder = CSIVolume.newBuilder
            .setPluginName(info.pluginName)
            .setStaticProvisioning(staticProvisioning)

          // TODO validate that Volume.readOnly is unset for RAML (we may need to make this optional for internal model)
          val mode = VolumeMount.readOnlyToProto(mount.readOnly)
          MesosVolume.newBuilder
            .setContainerPath(mount.mountPath)
            .setMode(mode)
            .setSource(
              MesosVolume.Source.newBuilder
                .setType(MesosVolume.Source.Type.CSI_VOLUME)
                .setCsiVolume(volBuilder.build)
            )
            .build
      }
    }
  } // Builders

  override def build(ev: ExternalVolume, mount: VolumeMount): MesosVolume =
    Builders.toUnifiedContainerVolume(ev, mount)
}

object CSIProviderValidations extends ExternalVolumeValidations {
  override def rootGroup: Validator[RootGroup] = ValidationHelpers.validateUniqueVolumes(CSIProvider.name)

  override def app: Validator[AppDefinition] = { _ => Success }

  override def volume: Validator[ExternalVolume] = { _ =>
    // TODO - we need to validate that the volume mode agrees with the access mode
    Success
  }

  override def ramlVolume(container: AppContainer): Validator[AppExternalVolume] = { _ => Success }

  override def ramlApp: Validator[App] = { _ => Success }
}
