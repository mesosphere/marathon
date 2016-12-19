package mesosphere.marathon
package raml

import mesosphere.marathon.core.pod.{ EphemeralVolume, HostVolume, Volume => PodVolume }
import mesosphere.marathon.state.DiskType
import org.apache.mesos.{ Protos => Mesos }

trait VolumeConversion extends ConstraintConversion with DefaultConversions {

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

  implicit val volumeWrites: Writes[state.Volume, AppVolume] = Writes { volume =>

    implicit val volumeModeWrites: Writes[Mesos.Volume.Mode, ReadMode] = Writes {
      case Mesos.Volume.Mode.RO => ReadMode.Ro
      case Mesos.Volume.Mode.RW => ReadMode.Rw
    }

    implicit val externalVolumeWrites: Writes[state.ExternalVolumeInfo, ExternalVolume] = Writes { ev =>
      ExternalVolume(size = ev.size, name = Some(ev.name), provider = Some(ev.provider), options = ev.options)
    }

    implicit val persistentVolumeInfoWrites: Writes[state.PersistentVolumeInfo, PersistentVolume] = Writes { pv =>
      val pvType = Option(pv.`type` match {
        case DiskType.Mount => PersistentVolumeType.Mount
        case DiskType.Path => PersistentVolumeType.Path
        case DiskType.Root => PersistentVolumeType.Root
      })
      PersistentVolume(pvType, pv.size, pv.maxSize, pv.constraints.toRaml[Set[Seq[String]]])
    }

    def create(hostPath: Option[String] = None, persistent: Option[PersistentVolume] = None, external: Option[ExternalVolume] = None): AppVolume = AppVolume(
      containerPath = volume.containerPath,
      hostPath = hostPath,
      persistent = persistent,
      external = external,
      mode = volume.mode.toRaml
    )

    volume match {
      case dv: state.DockerVolume => create(Some(dv.hostPath))
      case ev: state.ExternalVolume => create(external = Some(ev.external.toRaml))
      case pv: state.PersistentVolume => create(persistent = Some(pv.persistent.toRaml))
    }
  }
}

object VolumeConversion extends VolumeConversion
