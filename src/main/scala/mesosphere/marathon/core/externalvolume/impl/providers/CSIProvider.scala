package mesosphere.marathon.core.externalvolume.impl.providers

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.serialization.{ExternalVolumeInfoSerializer, SecretSerializer}
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.core.externalvolume.impl.{ExternalVolumeProvider, ExternalVolumeValidations}
import mesosphere.marathon.raml.{App, AppExternalVolume, Container => AppContainer}
import mesosphere.marathon.state._
import org.apache.mesos.Protos.{Volume => MesosVolume}

import scala.jdk.CollectionConverters._

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
        case _: DVDIExternalVolumeInfo =>
          throw new IllegalStateException("Bug: DVDIProviderValidations should be used for DVDIExternalVolumeInfo")
        case info: CSIExternalVolumeInfo =>
          val staticProvisioning = CSIVolume.StaticProvisioning
            .newBuilder()
            .setVolumeId(info.name)
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

object CSIProviderValidations extends ExternalVolumeValidations with Validation {
  override def rootGroup: Validator[RootGroup] = ProviderValidationHelpers.validateUniqueVolumes(CSIProvider.name)

  private def scalingNotAllowedForUnsharedVolumes: Validator[AppDefinition] =
    validator[AppDefinition] { appDef =>
      appDef.instances should withHint(WhileNonShareableCSIVolumesExistMessage, be <= 1)
    }

  private def scalingNotAllowedForReadWriteSingleWriterVolumes: Validator[AppDefinition] =
    validator[AppDefinition] { appDef =>
      appDef.instances should withHint(WhileWritableSingleWriterVolumesExistMessage, be <= 1)
    }

  override def app: Validator[AppDefinition] = { appDefinition =>
    val volumes = appDefinition.container.map(_.volumes).getOrElse(Nil)
    val csiVolumes = volumes.collect { case VolumeWithMount(ExternalVolume(_, csi: CSIExternalVolumeInfo), mount) => mount -> csi }

    val canScale = csiVolumes.forall(_._2.accessMode.shareable)

    val cannotScaleForSingleWriterMultiReader = csiVolumes.forall {
      case (mount, csi) =>
        (!mount.readOnly) && csi.accessMode == CSIExternalVolumeInfo.AccessMode.MULTI_NODE_SINGLE_WRITER
    }

    if (!canScale) {
      scalingNotAllowedForUnsharedVolumes(appDefinition)
    } else if (cannotScaleForSingleWriterMultiReader) {
      scalingNotAllowedForReadWriteSingleWriterVolumes(appDefinition)
    } else {
      Success
    }
  }

  override def volume(volumeMount: VolumeMount): Validator[ExternalVolume] = { ev =>
    // TODO - we need to validate that the volume mode agrees with the access mode
    ev.external match {
      case csi: CSIExternalVolumeInfo =>
        if (!volumeMount.readOnly && csi.accessMode.readOnly) {
          Failure(Set(RuleViolation(None, CSIMountReadonlyForReadonlyAccessModeMessage)))
        } else {
          Success
        }

      case dvdi: DVDIExternalVolumeInfo =>
        throw new IllegalStateException("Bug. CSIVolumeValidator applied to DVDI external volume")
    }
  }

  override def ramlVolume(container: AppContainer): Validator[AppExternalVolume] = { _ => Success }

  override def ramlApp: Validator[App] = { _ => Success }

  val WhileNonShareableCSIVolumesExistMessage = "while non-shareable CSI volumes are defined"
  val CSIMountReadonlyForReadonlyAccessModeMessage = "CSI volume must be mounted readonly with a read-only access mode"
  val WhileWritableSingleWriterVolumesExistMessage = "while a multi-node single-writer volume is defined mounted as read/write"
}
