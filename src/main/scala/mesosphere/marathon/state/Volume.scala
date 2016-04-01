package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.Protos
import org.apache.mesos.Protos.Volume.Mode
import org.apache.mesos.{ Protos => Mesos }

sealed trait Volume {
  def containerPath: String
  def mode: Mesos.Volume.Mode
}

object Volume {
  def apply(
    containerPath: String,
    hostPath: Option[String],
    mode: Mesos.Volume.Mode,
    persistent: Option[PersistentVolumeInfo]): Volume =
    persistent match {
      case Some(persistentVolumeInfo) =>
        PersistentVolume(
          containerPath = containerPath,
          persistent = persistentVolumeInfo,
          mode = mode
        )
      case None =>
        DockerVolume(
          containerPath = containerPath,
          hostPath = hostPath.getOrElse(""),
          mode = mode
        )
    }

  def apply(proto: Protos.Volume): Volume = {
    val persistent: Option[PersistentVolumeInfo] =
      if (proto.hasPersistent) Some(PersistentVolumeInfo(proto.getPersistent.getSize)) else None

    persistent match {
      case Some(persistentVolumeInfo) =>
        PersistentVolume(
          containerPath = proto.getContainerPath,
          persistent = persistentVolumeInfo,
          mode = proto.getMode
        )
      case None =>
        DockerVolume(
          containerPath = proto.getContainerPath,
          hostPath = proto.getHostPath,
          mode = proto.getMode
        )
    }
  }

  def apply(proto: Mesos.Volume): Volume =
    DockerVolume(
      containerPath = proto.getContainerPath,
      hostPath = proto.getHostPath,
      mode = proto.getMode
    )

  def unapply(volume: Volume): Option[(String, Option[String], Mesos.Volume.Mode, Option[PersistentVolumeInfo])] =
    volume match {
      case persistentVolume: PersistentVolume =>
        Some((persistentVolume.containerPath, None, persistentVolume.mode, Some(persistentVolume.persistent)))
      case dockerVolume: DockerVolume =>
        Some((dockerVolume.containerPath, Some(dockerVolume.hostPath), dockerVolume.mode, None))
    }

  implicit val validVolume: Validator[Volume] = new Validator[Volume] {
    override def apply(volume: Volume): Result = volume match {
      case pv: PersistentVolume => validate(pv)(PersistentVolume.validPersistentVolume)
      case dv: DockerVolume     => validate(dv)(DockerVolume.validDockerVolume)
    }
  }
}

/**
  * A volume mapping either from host to container or vice versa.
  * Both paths can either refer to a directory or a file.  Paths must be
  * absolute.
  */
case class DockerVolume(
  containerPath: String,
  hostPath: String,
  mode: Mesos.Volume.Mode)
    extends Volume

object DockerVolume {

  implicit val validDockerVolume = validator[DockerVolume] { vol =>
    vol.containerPath is notEmpty
    vol.hostPath is notEmpty
    vol.mode is oneOf(Mode.RW, Mode.RO)
  }
}

case class PersistentVolumeInfo(size: Long)

object PersistentVolumeInfo {
  implicit val validPersistentVolumeInfo = validator[PersistentVolumeInfo] { info =>
    info.size should be > 0L
  }
}

case class PersistentVolume(
  containerPath: String,
  persistent: PersistentVolumeInfo,
  mode: Mesos.Volume.Mode)
    extends Volume

object PersistentVolume {
  import org.apache.mesos.Protos.Volume.Mode
  implicit val validPersistentVolume = validator[PersistentVolume] { vol =>
    vol.containerPath is notEmpty
    vol.persistent is valid
    vol.mode is equalTo(Mode.RW)
    //persistent volumes require those CLI parameters provided
    vol is configValueSet("mesos_authentication_principal", "mesos_role", "mesos_authentication_secret_file")
  }
}
