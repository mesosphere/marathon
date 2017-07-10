package mesosphere.marathon
package raml

import mesosphere.marathon.core.pod

import mesosphere.marathon.state.{ DiskType, ExternalVolumeInfo, PersistentVolumeInfo, SecretVolume }
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.Implicits._
import org.apache.mesos.{ Protos => Mesos }

trait VolumeConversion extends ConstraintConversion with DefaultConversions {

  implicit val volumeRamlReader: Reads[PodVolume, pod.Volume] = Reads {
    case ev: EphemeralVolume => core.pod.EphemeralVolume(ev.name)
    case hv: HostVolume => pod.HostVolume(hv.name, hv.host)
    case sv: PodSecretVolume =>
      core.pod.SecretVolume(sv.name, sv.secret)
  }

  implicit val volumeRamlWriter: Writes[pod.Volume, PodVolume] = Writes {
    case e: pod.EphemeralVolume => raml.EphemeralVolume(e.name)
    case h: pod.HostVolume => raml.HostVolume(h.name, h.hostPath)
    case s: pod.SecretVolume => raml.PodSecretVolume(s.name, s.secret)
  }

  implicit val volumeModeWrites: Writes[Mesos.Volume.Mode, ReadMode] = Writes {
    case Mesos.Volume.Mode.RO => ReadMode.Ro
    case Mesos.Volume.Mode.RW => ReadMode.Rw
  }

  implicit val volumeModeReads: Reads[ReadMode, Mesos.Volume.Mode] = Reads {
    case ReadMode.Ro => Mesos.Volume.Mode.RO
    case ReadMode.Rw => Mesos.Volume.Mode.RW
  }

  implicit val volumeWrites: Writes[state.Volume, AppVolume] = Writes { volume =>

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

    volume match {
      case dv: state.DockerVolume => AppDockerVolume(
        volume.containerPath,
        dv.hostPath,
        mode = volume.mode.toRaml)
      case ev: state.ExternalVolume => AppExternalVolume(
        volume.containerPath,
        external = ev.external.toRaml,
        mode = volume.mode.toRaml)
      case pv: state.PersistentVolume => AppPersistentVolume(
        volume.containerPath,
        persistent = pv.persistent.toRaml,
        mode = volume.mode.toRaml)
      case sv: state.SecretVolume => AppSecretVolume(
        volume.containerPath,
        secret = sv.secret
      )
    }
  }

  implicit val volumeReads: Reads[AppVolume, state.Volume] = Reads {
    case v: AppExternalVolume => volumeExternalReads.read(v)
    case v: AppPersistentVolume => volumePersistentReads.read(v)
    case v: AppDockerVolume => volumeDockerReads.read(v)
    case v: AppSecretVolume => volumeSecretReads.read(v)
    case unsupported => throw SerializationFailedException(s"unsupported app volume type $unsupported")
  }

  implicit val volumeExternalReads: Reads[AppExternalVolume, state.Volume] = Reads { vol =>
    val info = ExternalVolumeInfo(
      size = vol.external.size,
      name = vol.external.name.getOrElse(throw SerializationFailedException("external volume requires a name")),
      provider = vol.external.provider.getOrElse(throw SerializationFailedException("external volume requires a provider")),
      options = vol.external.options
    )
    state.ExternalVolume(containerPath = vol.containerPath, external = info, mode = vol.mode.fromRaml)
  }

  implicit val volumePersistentReads: Reads[AppPersistentVolume, state.Volume] = Reads { vol =>
    val persistent = vol.persistent
    val volType = persistent.`type` match {
      case Some(definedType) => definedType match {
        case PersistentVolumeType.Root => DiskType.Root
        case PersistentVolumeType.Mount => DiskType.Mount
        case PersistentVolumeType.Path => DiskType.Path
      }
      case None => DiskType.Root
    }
    val info = PersistentVolumeInfo(
      size = persistent.size,
      maxSize = persistent.maxSize,
      `type` = volType,
      constraints = persistent.constraints.map { constraint =>
        (constraint.headOption, constraint.lift(1), constraint.lift(2)) match {
          case (Some("path"), Some("LIKE"), Some(value)) =>
            Protos.Constraint.newBuilder()
              .setField("path")
              .setOperator(Protos.Constraint.Operator.LIKE)
              .setValue(value)
              .build()
          case _ =>
            throw SerializationFailedException(s"illegal volume constraint ${constraint.mkString(",")}")
        }
      }(collection.breakOut)
    )
    state.PersistentVolume(containerPath = vol.containerPath, persistent = info, mode = vol.mode.fromRaml)
  }

  implicit val volumeDockerReads: Reads[AppDockerVolume, state.Volume] = Reads { vol =>
    state.DockerVolume(containerPath = vol.containerPath, hostPath = vol.hostPath, mode = vol.mode.fromRaml)
  }

  implicit val volumeSecretReads: Reads[AppSecretVolume, state.Volume] = Reads { vol =>
    SecretVolume(vol.containerPath, vol.secret)
  }

  implicit val appVolumeExternalProtoRamlWriter: Writes[Protos.Volume.ExternalVolumeInfo, ExternalVolume] = Writes { vol =>
    ExternalVolume(
      size = vol.when(_.hasSize, _.getSize).orElse(ExternalVolume.DefaultSize),
      name = vol.when(_.hasName, _.getName).orElse(ExternalVolume.DefaultName),
      provider = vol.when(_.hasProvider, _.getProvider).orElse(ExternalVolume.DefaultProvider),
      options = vol.whenOrElse(_.getOptionsCount > 0, _.getOptionsList.map { x => x.getKey -> x.getValue }(collection.breakOut), ExternalVolume.DefaultOptions)
    )
  }

  implicit val appPersistentVolTypeProtoRamlWriter: Writes[Mesos.Resource.DiskInfo.Source.Type, PersistentVolumeType] = Writes { typ =>
    import Mesos.Resource.DiskInfo.Source.Type._
    typ match {
      case MOUNT => PersistentVolumeType.Mount
      case PATH => PersistentVolumeType.Path
      case badType => throw new IllegalStateException(s"unsupported Mesos resource disk-info source type $badType")
    }
  }

  implicit val appVolumePersistentProtoRamlWriter: Writes[Protos.Volume.PersistentVolumeInfo, PersistentVolume] = Writes { vol =>
    PersistentVolume(
      `type` = vol.when(_.hasType, _.getType.toRaml).orElse(PersistentVolume.DefaultType),
      size = vol.getSize,
      maxSize = vol.when(_.hasMaxSize, _.getMaxSize).orElse(PersistentVolume.DefaultMaxSize), // TODO(jdef) protobuf serialization is broken for this
      constraints = vol.whenOrElse(_.getConstraintsCount > 0, _.getConstraintsList.map(_.toRaml[Seq[String]])(collection.breakOut), PersistentVolume.DefaultConstraints)
    )
  }

  implicit val appVolumeProtoRamlWriter: Writes[Protos.Volume, AppVolume] = Writes {
    case vol if vol.hasExternal => AppExternalVolume(
      containerPath = vol.getContainerPath,
      external = vol.getExternal.toRaml,
      mode = vol.getMode.toRaml
    )
    case vol if vol.hasPersistent => AppPersistentVolume(
      containerPath = vol.getContainerPath,
      persistent = vol.getPersistent.toRaml,
      mode = vol.getMode.toRaml
    )
    case vol if vol.hasSecret => AppSecretVolume(
      containerPath = vol.getContainerPath,
      secret = vol.getSecret.getSecret
    )
    case vol => AppDockerVolume(
      containerPath = vol.getContainerPath,
      hostPath = vol.getHostPath,
      mode = vol.getMode.toRaml
    )
  }
}

object VolumeConversion extends VolumeConversion
