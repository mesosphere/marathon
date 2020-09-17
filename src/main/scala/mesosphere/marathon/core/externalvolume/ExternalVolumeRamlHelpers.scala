package mesosphere.marathon.core.externalvolume

import mesosphere.marathon.raml.{CSIExternalVolumeInfo, ExternalVolumeInfo, GenericExternalVolumeInfo}

object ExternalVolumeRamlHelpers {
  def getName(externalVolume: ExternalVolumeInfo): Option[String] = {
    externalVolume match {
      case csi: CSIExternalVolumeInfo => Some(csi.name)
      case generic: GenericExternalVolumeInfo => generic.name
    }
  }

  def getProvider(externalVolume: ExternalVolumeInfo): Option[String] =
    externalVolume match {
      case csi: CSIExternalVolumeInfo => Some(csi.provider)
      case generic: GenericExternalVolumeInfo => generic.provider
    }
}
