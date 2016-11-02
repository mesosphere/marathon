package mesosphere.marathon
package raml

import mesosphere.marathon.core.pod.{ EphemeralVolume, HostVolume, Volume => PodVolume }
import mesosphere.marathon.state.{ DiskType, ExternalVolumeInfo, PersistentVolumeInfo }
import mesosphere.marathon.stream.Implicits._
import mesosphere.mesos.protos.Implicits._
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

  implicit val volumeReads: Reads[AppVolume, state.Volume] = Reads { vol =>
    def failed[T](msg: String): T =
      throw SerializationFailedException(msg)

    val result: state.Volume = vol match {
      case AppVolume(ctPath, hostPath, None, Some(external), mode) =>
        val info = Some(ExternalVolumeInfo(
          size = external.size,
          name = external.name.getOrElse(failed("external volume requires a name")),
          provider = external.provider.getOrElse(failed("external volume requires a provider")),
          options = external.options
        ))
        state.Volume(containerPath = ctPath, hostPath = hostPath, mode = mode.fromRaml, persistent = None, external = info)
      case AppVolume(ctPath, hostPath, Some(persistent), None, mode) =>
        val volType = persistent.`type` match {
          case Some(definedType) => definedType match {
            case PersistentVolumeType.Root => DiskType.Root
            case PersistentVolumeType.Mount => DiskType.Mount
            case PersistentVolumeType.Path => DiskType.Path
          }
          case None => DiskType.Root
        }
        val info = Some(PersistentVolumeInfo(
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
        ))
        state.Volume(containerPath = ctPath, hostPath = hostPath, mode = mode.fromRaml, persistent = info, external = None)
      case AppVolume(ctPath, hostPath, None, None, mode) =>
        state.Volume(containerPath = ctPath, hostPath = hostPath, mode = mode.fromRaml, persistent = None, external = None)
      case v => failed(s"illegal volume specification $v")
    }
    result
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

  implicit val appVolumeProtoRamlWriter: Writes[Protos.Volume, AppVolume] = Writes { vol =>
    AppVolume(
      containerPath = vol.getContainerPath,
      hostPath = vol.when(_.hasHostPath, _.getHostPath).orElse(AppVolume.DefaultHostPath),
      persistent = vol.when(_.hasPersistent, _.getPersistent.toRaml).orElse(AppVolume.DefaultPersistent),
      external = vol.when(_.hasExternal, _.getExternal.toRaml).orElse(AppVolume.DefaultExternal),
      mode = vol.getMode.toRaml
    )
  }
}

object VolumeConversion extends VolumeConversion
