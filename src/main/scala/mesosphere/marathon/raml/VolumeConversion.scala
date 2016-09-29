package mesosphere.marathon.raml

import mesosphere.marathon.core.pod.{ EphemeralVolume, HostVolume, Volume => PodVolume }

trait VolumeConversion {

  implicit val volumeRamlReader: Reads[Volume, PodVolume] = Reads { v =>
    v.host match {
      case Some(hostPath) => HostVolume(v.name, hostPath)
      case None => EphemeralVolume(v.name)
    }
  }

  implicit val volumeRamlWriter: Writes[PodVolume, Volume] = Writes {
    case e: EphemeralVolume => Volume(e.name)
    case h: HostVolume => Volume(h.name, Some(h.hostPath))
  }
}

object VolumeConversion extends VolumeConversion
