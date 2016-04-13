package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.{ Features, Protos }
import mesosphere.marathon.api.v2.Validation.oneOf
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import org.apache.mesos.Protos.Volume.Mode
import org.apache.mesos.{ Protos => Mesos }
import scala.collection.JavaConverters._

sealed trait Volume {
  def containerPath: String
  def mode: Mesos.Volume.Mode
}

object Volume {
  def apply(
    containerPath: String,
    hostPath: Option[String],
    mode: Mesos.Volume.Mode,
    persistent: Option[PersistentVolumeInfo],
    external: Option[ExternalVolumeInfo]): Volume = {

    persistent match {
      case Some(persistentVolumeInfo) =>
        if (hostPath.isDefined) throw new IllegalArgumentException("hostPath may not be set with persistent")
        if (external.isDefined) throw new IllegalArgumentException("external may not be set with persistent")
        PersistentVolume(
          containerPath = containerPath,
          persistent = persistentVolumeInfo,
          mode = mode
        )
      case None =>
        external match {
          case Some(externalVolumeInfo) =>
            if (hostPath.isDefined) throw new IllegalArgumentException("hostPath may not be set with persistent")
            ExternalVolume(
              containerPath = containerPath,
              external = external.get,
              mode = mode
            )
          case None =>
            DockerVolume(
              containerPath = containerPath,
              hostPath = hostPath.getOrElse(""),
              mode = mode
            )
        }
    }
  }

  def apply(proto: Protos.Volume): Volume = {
    if (proto.hasPersistent)
      PersistentVolume(
        containerPath = proto.getContainerPath,
        persistent = PersistentVolumeInfo.fromProto(proto.getPersistent),
        mode = proto.getMode
      )
    else if (proto.hasExternal)
      ExternalVolume(
        containerPath = proto.getContainerPath,
        external = ExternalVolumeInfo.fromProto(proto.getExternal),
        mode = proto.getMode
      )
    else
      DockerVolume(
        containerPath = proto.getContainerPath,
        hostPath = proto.getHostPath,
        mode = proto.getMode
      )
  }

  type TupleV = (String, Option[String], Mesos.Volume.Mode, Option[PersistentVolumeInfo], Option[ExternalVolumeInfo])
  def unapply(volume: Volume): Option[TupleV] =
    volume match {
      case persistentVolume: PersistentVolume =>
        Some((persistentVolume.containerPath, None, persistentVolume.mode, Some(persistentVolume.persistent), None))
      case ev: ExternalVolume =>
        Some((ev.containerPath, None, ev.mode, None, Some(ev.external)))
      case dockerVolume: DockerVolume =>
        Some((dockerVolume.containerPath, Some(dockerVolume.hostPath), dockerVolume.mode, None, None))
    }

  implicit val validVolume: Validator[Volume] = new Validator[Volume] {
    override def apply(volume: Volume): Result = volume match {
      case pv: PersistentVolume => validate(pv)(PersistentVolume.validPersistentVolume)
      case dv: DockerVolume     => validate(dv)(DockerVolume.validDockerVolume)
      case ev: ExternalVolume   => validate(ev)(ExternalVolume.validExternalVolume)
    }
  }
}

/**
  * A volume mapping either from host to container or vice versa.
  * Both paths can either refer to a directory or a file. Paths must be
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
  def fromProto(pvi: Protos.Volume.PersistentVolumeInfo): PersistentVolumeInfo =
    new PersistentVolumeInfo(pvi.getSize)

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

  val NoSlashesPattern = """^[^/]*$""".r

  implicit val validPersistentVolume = validator[PersistentVolume] { vol =>
    vol.containerPath is notEmpty
    vol.containerPath is notOneOf(".", "..")
    vol.containerPath should matchRegexFully(NoSlashesPattern)
    vol.persistent is valid
    vol.mode is equalTo(Mode.RW)
  }
}

/**
  * ExternalVolumeInfo captures the specification for a volume that survives task restarts.
  *
  * `name` is the *unique name* of the storage volume. names should be treated as case insensitive labels
  * derived from an alpha-numeric character range [a-z0-9]. while there is no prescribed length limit for
  * volume names it has been observed that some storage provider implementations may refuse names greater
  * than 31 characters. YMMV. Although `name` is optional, some storage providers may require it.
  *
  * `name` uniqueness:
  *  <li> A volume name MUST be unique within the scope of a volume provider.
  *  <li> A fully-qualified volume name is expected to be unique across the cluster and may formed, for example,
  *       by concatenating the volume provider name with the volume name. E.g “dvdi.volume123”
  *
  * `provider` is optional; if specified it indicates which storage provider will implement volume
  * lifecycle management operations for the external volume. if unspecified, “agent” is assumed.
  * the provider names “dcos”, “agent”, and "docker" are currently reserved. The contents of provider
  * values are restricted to the alpha-numeric character range [a-z0-9].
  *
  * `options` contains provider-specific volume options. some items may be required in order for a volume
  * driver to function properly. Given a storage provider named “dvdi” all options specific to that
  * provider MUST be namespaced with a “dvdi/” prefix.
  *
  * future DCOS-specific options will be prefixed with “dcos/”. an example of a DCOS option might be
  * “dcos/label”, a user-assigned, human-friendly label that appears in a UI.
  *
  * @param size absolute size of the volume (MB)
  * @param name identifies the volume within the context of the storage provider.
  * @param provider identifies the storage provider responsible for volume lifecycle operations.
  * @param options contains storage provider-specific configuration configuration
  */
case class ExternalVolumeInfo(
  size: Option[Long] = None,
  name: String,
  provider: String,
  options: Map[String, String] = Map.empty[String, String])

object OptionLabelPatterns {
  val OptionNamespaceSeparator = "/"
  val OptionNamePattern = "[A-Za-z0-9](?:[-A-Za-z0-9\\._:]*[A-Za-z0-9])?"
  val LabelPattern = "[a-z0-9](?:[-a-z0-9]*[a-z0-9])?"
  val LabelRegex = "^" + LabelPattern + "$"
  val OptionKeyRegex = "^" + LabelPattern + OptionNamespaceSeparator + OptionNamePattern + "$"
}

object ExternalVolumeInfo {
  import OptionLabelPatterns._

  implicit val validOptions = validator[Map[String, String]] {
    option => option.keys.each should matchRegex(OptionKeyRegex)
  }

  implicit val validExternalVolumeInfo = validator[ExternalVolumeInfo] { info =>
    info.size.each should be > 0L
    info.name should matchRegex(LabelRegex)
    info.provider should matchRegex(LabelRegex)
    info.options is valid(validOptions)
  }

  def fromProto(evi: Protos.Volume.ExternalVolumeInfo): ExternalVolumeInfo =
    ExternalVolumeInfo(
      if (evi.hasSize) Some(evi.getSize) else None,
      evi.getName,
      evi.getProvider,
      evi.getOptionsList.asScala.map { p => p.getKey -> p.getValue }.toMap
    )
}

case class ExternalVolume(
  containerPath: String,
  external: ExternalVolumeInfo,
  mode: Mesos.Volume.Mode) extends Volume

object ExternalVolume {
  val validExternalVolume = validator[ExternalVolume] { ev =>
    ev is featureEnabled(Features.EXTERNAL_VOLUMES)
    ev.containerPath is notEmpty
    ev.external is valid(ExternalVolumeInfo.validExternalVolumeInfo)
  } and ExternalVolumes.validExternalVolume
}
