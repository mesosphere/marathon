package mesosphere.marathon
package core.externalvolume.impl.providers

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.core.externalvolume.ExternalVolumeRamlHelpers
import mesosphere.marathon.raml.{App, AppExternalVolume}
import mesosphere.marathon.state._

private[impl] object ProviderValidationHelpers {

  import mesosphere.marathon.api.v2.Validation._

  // group-level validation for CSI volumes: the same volume name may only be referenced by a single
  // task instance across the entire cluster.
  def validateUniqueVolumes(providerName: String): Validator[RootGroup] =
    (rootGroup: RootGroup) => {
      val appsByVolume: Map[String, Iterable[PathId]] =
        rootGroup.transitiveApps.flatMap { app => namesOfMatchingVolumes(providerName, app).map(_ -> app.id) }.groupBy {
          case (volumeName, _) => volumeName
        }.map { case (volumeName, volumes) => volumeName -> volumes.map { case (_, appId) => appId } }

      val appValid: Validator[AppDefinition] = {
        def volumeNameUnique(appId: PathId): Validator[ExternalVolume] = {
          def conflictingApps(vol: ExternalVolume): Iterable[PathId] =
            appsByVolume.getOrElse(vol.external.name, Iterable.empty).filter(_ != appId)

          isTrue { (vol: ExternalVolume) =>
            val conflictingAppIds = conflictingApps(vol).mkString(", ")
            s"Volume name '${vol.external.name}' in $appId conflicts with volume(s) of same name in app(s): " +
              s"$conflictingAppIds"
          } { vol => conflictingApps(vol).isEmpty }
        }

        def validContainerVolumes(appId: PathId): Validator[Container] = { container =>
          container.volumes.iterator
            .collect(externalVolumeWithMountPF(providerName))
            .filterNot(readOnlyMultiReader)
            .map { vwm => volumeNameUnique(appId)(vwm.volume) }
            .foldLeft(Success: Result) { _ and _ }
        }

        validator[AppDefinition] { app =>
          app.container is optional(validContainerVolumes(app.id))
        }
      }

      def groupValid: Validator[Group] =
        validator[Group] { group =>
          group.apps.values as "apps" is every(appValid)
          group.groupsById.values as "groups" is every(groupValid)
        }

      // We need to call the validators recursively such that the "description" of the rule violations
      // is correctly calculated.
      groupValid(rootGroup)
    }

  def readOnlyMultiReader(vwm: VolumeWithMount[ExternalVolume]) = {
    def isMulti =
      vwm.volume.external match {
        case csi: CSIExternalVolumeInfo =>
          csi.accessMode == CSIExternalVolumeInfo.AccessMode.MULTI_NODE_SINGLE_WRITER
        case _ => false
      }
    vwm.mount.readOnly && isMulti
  }

  /**
    * @return true if volume has a provider name that matches ours exactly
    */
  def matchesProvider(providerName: String, volume: ExternalVolume): Boolean = volume.external.provider == providerName

  def matchesProviderRaml(providerName: String, volume: AppExternalVolume): Boolean =
    ExternalVolumeRamlHelpers.getProvider(volume.external).contains(providerName)

  private def isSingleWriteable(am: CSIExternalVolumeInfo.AccessMode, isReadOnly: Boolean) =
    (!isReadOnly) && (am == CSIExternalVolumeInfo.AccessMode.MULTI_NODE_SINGLE_WRITER)
  private def isForUniquenessCheck(volume: ExternalVolume, mount: VolumeMount): Boolean =
    volume.external match {
      case csi: CSIExternalVolumeInfo =>
        val am = csi.accessMode
        !am.shareable || isSingleWriteable(am, mount.readOnly)
      case v: DVDIExternalVolumeInfo =>
        !v.shared
    }

  def isForUniquenessCheckRaml(volume: AppExternalVolume): Boolean =
    volume.external match {
      case external: raml.DVDIExternalVolumeInfo =>
        !external.shared
      case csi: raml.CSIExternalVolumeInfo =>
        val am =
          CSIExternalVolumeInfo.AccessMode.fromString(csi.options.capability.accessMode).getOrElse(CSIExternalVolumeInfo.AccessMode.UNKNOWN)
        !am.shareable
    }

  private def externalVolumeWithMountPF(providerName: String): PartialFunction[Any, VolumeWithMount[ExternalVolume]] = {
    case v @ VolumeWithMount(vol: ExternalVolume, mount) if matchesProvider(providerName, vol) =>
      v.asInstanceOf[VolumeWithMount[ExternalVolume]]
  }

  def namesOfMatchingVolumes(providerName: String, app: AppDefinition): Seq[String] =
    app.container
      .map(_.volumes)
      .getOrElse(Nil)
      .iterator
      .collect(externalVolumeWithMountPF(providerName))
      .filter { vwm => isForUniquenessCheck(vwm.volume, vwm.mount) }
      .map(_.volume.external.name)
      .toSeq

  def namesOfMatchingVolumes(providerName: String, app: App): Seq[String] =
    app.container
      .fold(Seq.empty[AppExternalVolume])(_.volumes.collect { case v: AppExternalVolume => v })
      .withFilter(matchesProviderRaml(providerName, _))
      .withFilter(isForUniquenessCheckRaml)
      .flatMap { v => ExternalVolumeRamlHelpers.getName(v.external) }
}
