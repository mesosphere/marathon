package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.Protos
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.volume.VolumesModule
import org.apache.mesos.Protos.Volume.Mode
import org.apache.mesos.{Protos => Mesos}

import scala.collection.JavaConverters._

sealed trait Volume {
  def containerPath: String
  def mode: Mesos.Volume.Mode
  def toProto: Protos.Volume
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

  def fromProto(proto: Protos.Volume): Volume = {
    if (proto.hasPersistent)
      PersistentVolume(
        containerPath = proto.getContainerPath,
        persistent = PersistentVolumeInfo.fromProto(proto.getPersistent),
        mode = proto.getMode
      )
    else
      DockerVolume(
        containerPath = proto.getContainerPath,
        hostPath = proto.getHostPath,
        mode = proto.getMode
      )
  }

  def unapply(volume: Volume): Option[(String, Option[String], Mesos.Volume.Mode, Option[PersistentVolumeInfo])] =
    volume match {
      case persistentVolume: PersistentVolume =>
        Some((persistentVolume.containerPath, None, persistentVolume.mode, Some(persistentVolume.persistent)))
      case dockerVolume: DockerVolume =>
        Some((dockerVolume.containerPath, Some(dockerVolume.hostPath), dockerVolume.mode, None))
    }

  implicit val validVolume: Validator[Volume] = new Validator[Volume] {
    override def apply(volume: Volume): Result = {
      volume match {
        case pv: PersistentVolume => validate(pv)(PersistentVolume.validPersistentVolume)
        case dv: DockerVolume     => validate(dv)(DockerVolume.validDockerVolume)
      }
    } and validate(volume)(validator[Volume] { v => VolumesModule.providers(v).isDefined is true })
  }
}

/**
  * A volume mapping either from host to container or vice versa.
  * Both paths can either refer to a directory or a file. Paths must be
  * absolute.
  *
  * TODO(jdef) rename this as DockerHostVolume for clarity
  */
case class DockerVolume(
  containerPath: String,
  hostPath: String,
  mode: Mesos.Volume.Mode)
    extends Volume {
  override def toProto: Protos.Volume =
    Protos.Volume.newBuilder()
      .setContainerPath(containerPath)
      .setHostPath(hostPath)
      .setMode(mode)
      .build()
}

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
  * the provider names “dcos”, “agent”, and "docker" are currently reserved. The contents of providerName
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
  * @param providerName identifies the storage provider responsible for volume lifecycle operations.
  * @param options contains storage provider-specific configuration configuration
  */
case class PersistentVolumeInfo(
    size: Option[Long] = None,
    name: Option[String] = None,
    providerName: Option[String] = None,
    options: Map[String, String] = Map.empty[String, String]) {
  def toProto: Protos.Volume.PersistentVolumeInfo = {
    val builder = Protos.Volume.PersistentVolumeInfo.newBuilder()
    size.foreach(builder.setSize)
    name.foreach(builder.setName)
    providerName.foreach(builder.setProviderName)
    options.map{ case (key, value) => Mesos.Label.newBuilder().setKey(key).setValue(value).build }
      .foreach(builder.addOptions)
    builder.build
  }
}

object OptionLabelPatterns {
  val OptionNamespaceSeparator = "/"
  val OptionNamePattern = "[A-Za-z0-9](?:[-A-Za-z0-9\\._:]*[A-Za-z0-9])?"
  val LabelPattern = "[a-z0-9](?:[-a-z0-9]*[a-z0-9])?"

  val LabelRegex = "^" + LabelPattern + "$"
  val OptionKeyRegex = "^" + LabelPattern + OptionNamespaceSeparator + OptionNamePattern + "$"
}

object PersistentVolumeInfo {
  import OptionLabelPatterns._

  implicit val validOptions = validator[Map[String, String]] {
    option => option.keys.each should matchRegex(OptionKeyRegex)
  }

  implicit val validPersistentVolumeInfo = validator[PersistentVolumeInfo] { info =>
    info.size.each should be > 0L
    info.name.each should matchRegex(LabelRegex)
    info.providerName.each should matchRegex(LabelRegex)
    info.options is valid(validOptions)
  }

  def fromProto(pvi: Protos.Volume.PersistentVolumeInfo): PersistentVolumeInfo =
    PersistentVolumeInfo(
      if (pvi.hasSize) Some(pvi.getSize) else None,
      if (pvi.hasName) Some(pvi.getName) else None,
      if (pvi.hasProviderName) Some(pvi.getProviderName) else None,
      pvi.getOptionsList.asScala.map { p => p.getKey -> p.getValue }.toMap
    )
}

case class PersistentVolume(
  containerPath: String,
  persistent: PersistentVolumeInfo,
  mode: Mesos.Volume.Mode)
    extends Volume {
  override def toProto: Protos.Volume =
    Protos.Volume.newBuilder()
      .setContainerPath(containerPath)
      .setPersistent(persistent.toProto)
      .setMode(mode)
      .build()
}

object PersistentVolume {
  implicit val validPersistentVolume = validator[PersistentVolume] { vol =>
    vol.containerPath is notEmpty
    vol.persistent is valid
    vol.persistent.providerName is VolumesModule.providers.known
    vol is VolumesModule.providers.approved(vol.persistent.providerName)
  }
}
