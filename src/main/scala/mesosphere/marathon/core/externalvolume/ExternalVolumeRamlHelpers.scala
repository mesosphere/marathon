package mesosphere.marathon.core.externalvolume

import mesosphere.marathon.raml.{CSIExternalVolumeInfo, ExternalVolumeInfo, DVDIExternalVolumeInfo}

object ExternalVolumeRamlHelpers {
  def getName(externalVolume: ExternalVolumeInfo): Option[String] = {
    externalVolume match {
      case csi: CSIExternalVolumeInfo => Some(csi.name)
      case dvdi: DVDIExternalVolumeInfo => dvdi.name
    }
  }
  def getProvider(externalVolume: ExternalVolumeInfo): Option[String] =
    externalVolume match {
      case csi: CSIExternalVolumeInfo => Some(csi.provider)
      case dvdi: DVDIExternalVolumeInfo => dvdi.provider
    }
}
