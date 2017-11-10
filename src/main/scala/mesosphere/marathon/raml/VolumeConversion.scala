package mesosphere.marathon
package raml

import com.fasterxml.uuid.Generators
import mesosphere.marathon.state.DiskType
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.Implicits._
import org.apache.mesos.{ Protos => Mesos }

trait VolumeConversion extends ConstraintConversion with DefaultConversions {

  implicit val volumeRamlReader: Reads[PodVolume, state.Volume] = Reads {
    case ev: PodEphemeralVolume => state.EphemeralVolume(name = Some(ev.name))
    case hv: PodHostVolume => state.HostVolume(name = Some(hv.name), hostPath = hv.host)
    case sv: PodSecretVolume => state.SecretVolume(name = Some(sv.name), secret = sv.secret)
    case pv: PodPersistentVolume =>
      val persistentInfo = state.PersistentVolumeInfo(
        `type` = pv.persistent.`type`.fromRaml,
        size = pv.persistent.size,
        maxSize = pv.persistent.maxSize,
        constraints = pv.persistent.constraints.fromRaml
      )
      state.PersistentVolume(name = Some(pv.name), persistent = persistentInfo)
  }

  implicit val volumeRamlWriter: Writes[state.Volume, PodVolume] = Writes {
    case e: state.EphemeralVolume => raml.PodEphemeralVolume(
      name = e.name.getOrElse(throw new IllegalArgumentException("name must not be empty")))
    case h: state.HostVolume => raml.PodHostVolume(
      name = h.name.getOrElse(throw new IllegalArgumentException("name must not be empty")), host = h.hostPath)
    case s: state.SecretVolume => raml.PodSecretVolume(
      name = s.name.getOrElse(throw new IllegalArgumentException("name must not be empty")), secret = s.secret)
    case p: state.PersistentVolume => raml.PodPersistentVolume(
      name = p.name.getOrElse(throw new IllegalArgumentException("name must not be empty")),
      persistent = p.persistent.toRaml)
  }

  implicit val volumeModeWrites: Writes[Mesos.Volume.Mode, ReadMode] = Writes {
    case Mesos.Volume.Mode.RO => ReadMode.Ro
    case Mesos.Volume.Mode.RW => ReadMode.Rw
  }

  implicit val volumeModeReads: Reads[ReadMode, Mesos.Volume.Mode] = Reads {
    case ReadMode.Ro => Mesos.Volume.Mode.RO
    case ReadMode.Rw => Mesos.Volume.Mode.RW
  }

  implicit val readOnlyFlagReads: Reads[ReadMode, Boolean] = Reads {
    case ReadMode.Ro => true
    case ReadMode.Rw => false
  }

  implicit val readOnlyFlagWrites: Writes[Boolean, ReadMode] = Writes { readOnly =>
    if (readOnly) ReadMode.Ro else ReadMode.Rw
  }

  implicit val persistentVolumeInfoWrites: Writes[state.PersistentVolumeInfo, PersistentVolumeInfo] = Writes { pv =>
    val pvType = Option(pv.`type` match {
      case DiskType.Mount => PersistentVolumeType.Mount
      case DiskType.Path => PersistentVolumeType.Path
      case DiskType.Root => PersistentVolumeType.Root
    })
    PersistentVolumeInfo(`type` = pvType, size = pv.size, maxSize = pv.maxSize,
      constraints = pv.constraints.toRaml[Set[Seq[String]]])
  }

  implicit val volumeWrites: Writes[state.VolumeWithMount, AppVolume] = Writes { volumeWithMount =>

    implicit val externalVolumeWrites: Writes[state.ExternalVolumeInfo, ExternalVolumeInfo] = Writes { ev =>
      ExternalVolumeInfo(size = ev.size, name = Some(ev.name), provider = Some(ev.provider), options = ev.options)
    }

    val volume = volumeWithMount.volume
    val mount = volumeWithMount.mount
    volume match {
      case dv: state.HostVolume => AppHostVolume(
        containerPath = mount.mountPath,
        hostPath = dv.hostPath,
        mode = mount.readOnly.toRaml)
      case ev: state.ExternalVolume => AppExternalVolume(
        containerPath = mount.mountPath,
        external = ev.external.toRaml,
        mode = mount.readOnly.toRaml)
      case pv: state.PersistentVolume => AppPersistentVolume(
        containerPath = mount.mountPath,
        persistent = pv.persistent.toRaml,
        mode = mount.readOnly.toRaml)
      case sv: state.SecretVolume => AppSecretVolume(
        containerPath = mount.mountPath,
        secret = sv.secret
      )
    }
  }

  implicit val volumeReads: Reads[AppVolume, state.VolumeWithMount] = Reads {
    case v: AppExternalVolume => volumeExternalReads.read(v)
    case v: AppPersistentVolume => volumePersistentReads.read(v)
    case v: AppHostVolume => volumeHostReads.read(v)
    case v: AppSecretVolume => volumeSecretReads.read(v)
    case unsupported => throw SerializationFailedException(s"unsupported app volume type $unsupported")
  }

  implicit val volumeExternalReads: Reads[AppExternalVolume, state.VolumeWithMount] = Reads { volumeRaml =>
    val info = state.ExternalVolumeInfo(
      size = volumeRaml.external.size,
      name = volumeRaml.external.name.getOrElse(
        throw SerializationFailedException("external volume requires a name")),
      provider = volumeRaml.external.provider.getOrElse(
        throw SerializationFailedException("external volume requires a provider")),
      options = volumeRaml.external.options
    )
    val volume = state.ExternalVolume(name = None, external = info)
    val mount = state.VolumeMount(
      volumeName = None, mountPath = volumeRaml.containerPath, readOnly = volumeRaml.mode.fromRaml)
    state.VolumeWithMount(volume = volume, mount = mount)
  }

  implicit val volumeTypeReads: Reads[Option[PersistentVolumeType], DiskType] = Reads {
    case Some(definedType) => definedType match {
      case PersistentVolumeType.Root => DiskType.Root
      case PersistentVolumeType.Mount => DiskType.Mount
      case PersistentVolumeType.Path => DiskType.Path
    }
    case None => DiskType.Root
  }

  implicit val volumeConstraintsReads: Reads[Set[Seq[String]], Set[Protos.Constraint]] = Reads { constraints =>
    constraints.map { constraint =>
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
  }

  implicit val volumePersistentReads: Reads[AppPersistentVolume, state.VolumeWithMount] = Reads { volumeRaml =>
    val info = state.PersistentVolumeInfo(
      `type` = volumeRaml.persistent.`type`.fromRaml,
      size = volumeRaml.persistent.size,
      maxSize = volumeRaml.persistent.maxSize,
      constraints = volumeRaml.persistent.constraints.fromRaml
    )
    val volume = state.PersistentVolume(name = None, persistent = info)
    val mount = state.VolumeMount(
      volumeName = None, mountPath = volumeRaml.containerPath, readOnly = volumeRaml.mode.fromRaml)
    state.VolumeWithMount(volume = volume, mount = mount)
  }

  implicit val volumeHostReads: Reads[AppHostVolume, state.VolumeWithMount] = Reads { volumeRaml =>
    val volume = state.HostVolume(name = None, hostPath = volumeRaml.hostPath)
    val mount = state.VolumeMount(
      volumeName = None, mountPath = volumeRaml.containerPath, readOnly = volumeRaml.mode.fromRaml)
    state.VolumeWithMount(volume = volume, mount = mount)
  }

  implicit val volumeSecretReads: Reads[AppSecretVolume, state.VolumeWithMount] = Reads { volumeRaml =>
    val volume = state.SecretVolume(name = None, secret = volumeRaml.secret)
    val mount = state.VolumeMount(volumeName = None, mountPath = volumeRaml.containerPath, readOnly = true)
    state.VolumeWithMount(volume = volume, mount = mount)
  }

  implicit val appVolumeExternalProtoRamlWriter: Writes[Protos.Volume.ExternalVolumeInfo, ExternalVolumeInfo] =
    Writes { volume =>
      ExternalVolumeInfo(
        size = volume.when(_.hasSize, _.getSize).orElse(ExternalVolumeInfo.DefaultSize),
        name = volume.when(_.hasName, _.getName).orElse(ExternalVolumeInfo.DefaultName),
        provider = volume.when(_.hasProvider, _.getProvider).orElse(ExternalVolumeInfo.DefaultProvider),
        options = volume.whenOrElse(
          _.getOptionsCount > 0,
          _.getOptionsList.map { x => x.getKey -> x.getValue }(collection.breakOut),
          ExternalVolumeInfo.DefaultOptions)
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

  implicit val appVolumePersistentProtoRamlWriter: Writes[Protos.Volume.PersistentVolumeInfo, PersistentVolumeInfo] =
    Writes { volume =>
      PersistentVolumeInfo(
        `type` = volume.when(_.hasType, _.getType.toRaml).orElse(PersistentVolumeInfo.DefaultType),
        size = volume.getSize,
        // TODO(jdef) protobuf serialization is broken for this
        maxSize = volume.when(_.hasMaxSize, _.getMaxSize).orElse(PersistentVolumeInfo.DefaultMaxSize),
        constraints = volume.whenOrElse(
          _.getConstraintsCount > 0,
          _.getConstraintsList.map(_.toRaml[Seq[String]])(collection.breakOut),
          PersistentVolumeInfo.DefaultConstraints)
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
    case vol => AppHostVolume(
      containerPath = vol.getContainerPath,
      hostPath = vol.getHostPath,
      mode = vol.getMode.toRaml
    )
  }
}

object VolumeConversion extends VolumeConversion
