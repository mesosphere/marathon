package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.Protos
import mesosphere.marathon.core.volume.Volumes
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
      if (proto.hasPersistent) Some(PersistentVolumeInfo(
        if (proto.getPersistent.hasSize) Some(proto.getPersistent.getSize) else None,
        if (proto.getPersistent.hasName) Some(proto.getPersistent.getName) else None,
        if (proto.getPersistent.hasProviderName) Some(proto.getPersistent.getProviderName) else None,
        if (proto.getPersistent.getOptionsCount() > 0)
          Some(proto.getPersistent.getOptionsList.asScala.map { p => p.getKey -> p.getValue }.toMap)
        else None
      ))
      else None

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

/**
  * PersistentVolumeInfo captures the specification for a volume that survives task restarts.
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
  * `providerName` is optional; if specified it indicates which storage provider will implement volume
  * lifecycle management operations for the persistent volume. if unspecified, “agent” is assumed.
  * the provider names “dcos”, “agent”, and are currently reserved. The contents of providerName values
  * are restricted to the alpha-numeric character range [a-z0-9].
  *
  * `options` contains provider-specific volume options. some items may be required in order for a volume
  * driver to function properly. Consider a storage provider named “dvdi”. all options specific to that
  * provider MUST be namespaced with a “dvdi/” prefix.
  *
  * DCOS-specific options will be prefixed with “dcos/”. an example of a DCOS option might be
  * “dcos/label”, a user-assigned, human-friendly label that appears in a UI.
  *
  * future: will contain a mix of DCOS, Marathon, and provider-specific options.
  *
  * @param size absolute size of the volume (MB)
  * @param name identifies the volume within the context of the storage provider.
  * @param providerName identifies the storage provider responsible for volume lifecycle operations.
  * @param options contains storage provider-specific configuration configuration
  */
case class PersistentVolumeInfo(
  size: Option[Long] = None,
  name: Option[String] = None,
  providerName: Option[String] = None,
  options: Option[Map[String, String]] = None) // = Map.empty[String, String])

object PersistentVolumeInfo {
  private val OptionNamespaceSeparator = "/"
  private val OptionNamePattern = "[A-Za-z0-9](?:[-A-Za-z0-9\\._:]*[A-Za-z0-9])?"
  private val LabelPattern = "[a-z0-9](?:[-a-z0-9]*[a-z0-9])?"

  private val LabelRegex = "^" + LabelPattern + "$"
  private val OptionKeyRegex = "^" + LabelRegex + OptionNamespaceSeparator + OptionNamePattern + "$"

  implicit val validOptions = validator[Map[String, String]] {
    option => option.keys.each should matchRegex(OptionKeyRegex)
  }

  implicit val validPersistentVolumeInfo = validator[PersistentVolumeInfo] { info =>
    info.size.each should be > 0L
    info.name.each should matchRegex(LabelRegex)
    info.providerName.each should matchRegex(LabelRegex)
    info.options.each is valid(validOptions)
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
    Volumes(vol.persistent.providerName).isDefined is true
    vol is valid(Volumes(vol.persistent.providerName).get.validation)
  }
}
