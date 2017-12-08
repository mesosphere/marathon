package mesosphere.marathon
package state

import com.wix.accord.Descriptions.{ Generic, Path => WixPath }
import java.util.regex.Pattern

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.stream.Implicits._
import org.apache.mesos.Protos.Resource.DiskInfo.Source
import org.apache.mesos.Protos.Volume.Mode

import scala.util.Try
import scala.util.matching.Regex

trait Volume extends plugin.VolumeSpec

object Volume {
  def apply(name: Option[String], proto: Protos.Volume): Volume = {
    if (proto.hasPersistent)
      PersistentVolume(
        name = name,
        persistent = PersistentVolumeInfo.fromProto(proto.getPersistent))
    else if (proto.hasExternal)
      ExternalVolume(
        name = name,
        external = ExternalVolumeInfo.fromProto(proto.getExternal))
    else if (proto.hasSecret)
      SecretVolume(
        name = name,
        secret = proto.getSecret.getSecret)
    else
      HostVolume(
        name = name,
        hostPath = proto.getHostPath)
  }

  def validVolume(enabledFeatures: Set[String]): Validator[Volume] = new Validator[Volume] {
    override def apply(volume: Volume): Result = volume match {
      case pv: PersistentVolume => validate(pv)(PersistentVolume.validPersistentVolume)
      case dv: HostVolume => validate(dv)(HostVolume.validHostVolume)
      case ev: ExternalVolume => validate(ev)(ExternalVolume.validExternalVolume(enabledFeatures))
      case _: SecretVolume => Success // validation is done in raml
    }
  }
}

case class VolumeMount(volumeName: Option[String], mountPath: String, readOnly: Boolean = false)
  extends plugin.VolumeMountSpec

object VolumeMount {
  def apply(volumeName: Option[String], proto: Protos.Volume): VolumeMount = {
    new VolumeMount(
      volumeName = volumeName,
      mountPath = proto.getContainerPath,
      readOnly = proto.getMode == Mode.RO
    )
  }

  def apply(volumeName: Option[String], mountPath: String, readOnly: Boolean = false): VolumeMount = {
    new VolumeMount(volumeName, mountPath, readOnly)
  }

  implicit val validVolumeMount: Validator[VolumeMount] = validator[VolumeMount] { mount =>
    mount.volumeName is optional(notEmpty)
    mount.mountPath is notEmpty
  }

  def readOnlyToProto(readOnly: Boolean): Mode = if (readOnly) Mode.RO else Mode.RW
}

case class VolumeWithMount(volume: Volume, mount: VolumeMount)

object VolumeWithMount {
  def apply(volumeName: Option[String], proto: Protos.Volume): VolumeWithMount =
    new VolumeWithMount(volume = Volume(volumeName, proto), mount = VolumeMount(volumeName, proto))

  def apply(volume: Volume, mount: VolumeMount): VolumeWithMount =
    new VolumeWithMount(volume = volume, mount = mount)

  def validVolumeWithMount(enabledFeatures: Set[String]): Validator[VolumeWithMount] = validator[VolumeWithMount] { vm =>
    vm.volume is Volume.validVolume(enabledFeatures)
    vm.mount is VolumeMount.validVolumeMount
    vm.volume match {
      case _: PersistentVolume =>
        vm.mount is PersistentVolume.validPersistentVolumeMount
      case _ =>
    }
  }
}

/**
  * A volume mapping either from host to container or vice versa.
  * Both paths can either refer to a directory or a file. Paths must be
  * absolute.
  */
case class HostVolume(name: Option[String], hostPath: String)
  extends Volume

object HostVolume {
  implicit val validHostVolume: Validator[HostVolume] = validator[HostVolume] { vol =>
    vol.name is optional(notEmpty)
    vol.hostPath is notEmpty
  }
}

case class DiskSource(diskType: DiskType, path: Option[String]) {
  if (diskType == DiskType.Root)
    require(path.isEmpty, "Path is not allowed for diskType")
  else
    require(path.isDefined, "Path is required for non-root diskTypes")

  override def toString: String =
    path match {
      case Some(p) => s"$diskType:$p"
      case None => diskType.toString
    }

  def asMesos: Option[Source] = (path, diskType) match {
    case (None, DiskType.Root) =>
      None
    case (Some(p), DiskType.Path | DiskType.Mount) =>
      val bld = Source.newBuilder
      diskType.toMesos.foreach(bld.setType)
      if (diskType == DiskType.Mount)
        bld.setMount(Source.Mount.newBuilder().setRoot(p))
      else
        bld.setPath(Source.Path.newBuilder().setRoot(p))
      Some(bld.build)
    case (_, _) =>
      throw new RuntimeException("invalid state")
  }
}

object DiskSource {
  val root = DiskSource(DiskType.Root, None)
  @SuppressWarnings(Array("OptionGet"))
  def fromMesos(source: Option[Source]): DiskSource = {
    val diskType = DiskType.fromMesosType(source.map(_.getType))
    diskType match {
      case DiskType.Root =>
        DiskSource(DiskType.Root, None)
      case DiskType.Mount =>
        DiskSource(DiskType.Mount, Some(source.get.getMount.getRoot))
      case DiskType.Path =>
        DiskSource(DiskType.Path, Some(source.get.getPath.getRoot))
    }
  }
}

sealed trait DiskType {
  def toMesos: Option[Source.Type]
}

object DiskType {
  case object Root extends DiskType {
    override def toString: String = "root"
    def toMesos: Option[Source.Type] = None
  }

  case object Path extends DiskType {
    override def toString: String = "path"
    def toMesos: Option[Source.Type] = Some(Source.Type.PATH)
  }

  case object Mount extends DiskType {
    override def toString: String = "mount"
    def toMesos: Option[Source.Type] = Some(Source.Type.MOUNT)
  }

  val all: List[DiskType] = Root :: Path :: Mount :: Nil

  def fromMesosType(o: Option[Source.Type]): DiskType =
    o match {
      case None => DiskType.Root
      case Some(Source.Type.PATH) => DiskType.Path
      case Some(Source.Type.MOUNT) => DiskType.Mount
      case Some(other) => throw new RuntimeException(s"unknown mesos disk type: $other")
    }
}

case class PersistentVolumeInfo(
    size: Long,
    maxSize: Option[Long] = None,
    `type`: DiskType = DiskType.Root,
    profileName: Option[String] = None,
    constraints: Set[Constraint] = Set.empty)

object PersistentVolumeInfo {
  def fromProto(pvi: Protos.Volume.PersistentVolumeInfo): PersistentVolumeInfo =
    new PersistentVolumeInfo(
      size = pvi.getSize,
      maxSize = if (pvi.hasMaxSize) Some(pvi.getMaxSize) else None,
      `type` = DiskType.fromMesosType(if (pvi.hasType) Some(pvi.getType) else None),
      profileName = if (pvi.hasProfileName) Some(pvi.getProfileName) else None,
      constraints = pvi.getConstraintsList.toSet
    )

  private val complyWithVolumeConstraintRules: Validator[Constraint] = new Validator[Constraint] {
    import Constraint.Operator._
    override def apply(c: Constraint): Result = {
      if (!c.hasField || !c.hasOperator) {
        Failure(Set(RuleViolation(c, "Missing field and operator")))
      } else if (c.getField != "path") {
        Failure(Set(RuleViolation(c, "Unsupported field", WixPath(Generic(c.getField)))))
      } else {
        c.getOperator match {
          case LIKE | UNLIKE =>
            if (c.hasValue) {
              Try(Pattern.compile(c.getValue)) match {
                case util.Success(_) =>
                  Success
                case util.Failure(e) =>
                  Failure(Set(RuleViolation(
                    c,
                    "Invalid regular expression",
                    WixPath(Generic("value")))))
              }
            } else {
              Failure(Set(RuleViolation(c, "A regular expression value must be provided", WixPath(Generic("value")))))
            }
          case _ =>
            Failure(Set(RuleViolation(c, "Operator must be one of LIKE, UNLIKE", WixPath(Generic("operator")))))
        }
      }
    }
  }

  implicit val validPersistentVolumeInfo: Validator[PersistentVolumeInfo] = {
    val notHaveConstraintsOnRoot = isTrue[PersistentVolumeInfo](
      "Constraints on root volumes are not supported") { info =>
        if (info.`type` == DiskType.Root)
          info.constraints.isEmpty
        else
          true
      }

    val meetMaxSizeConstraint = isTrue[PersistentVolumeInfo]("Only mount volumes can have maxSize") { info =>
      if (info.`type` == DiskType.Mount)
        true
      else
        info.maxSize.isEmpty
    }

    val haveProperlyOrderedMaxSize = isTrue[PersistentVolumeInfo]("Max size must be larger than size") { info =>
      info.maxSize.forall(_ > info.size)
    }

    val haveProperProfileName = validator[String] { profileName =>
      profileName is notEmpty
    }

    validator[PersistentVolumeInfo] { info =>
      info.size should be > 0L
      info.constraints.each must complyWithVolumeConstraintRules
      info should meetMaxSizeConstraint
      info should notHaveConstraintsOnRoot
      info should haveProperlyOrderedMaxSize
      info.profileName should optional(haveProperProfileName)
    }
  }
}

case class PersistentVolume(name: Option[String], persistent: PersistentVolumeInfo)
  extends Volume

object PersistentVolume {
  import PathPatterns._

  implicit val validPersistentVolume: Validator[PersistentVolume] = validator[PersistentVolume] { vol =>
    vol.name is optional(notEmpty)
    vol.persistent is valid
  }

  implicit val validPersistentVolumeMount: Validator[VolumeMount] = validator[VolumeMount] { mount =>
    mount.mountPath is notOneOf(DotPaths: _*)
    mount.mountPath should matchRegexWithFailureMessage(NoSlashesPattern, "value must not contain \"/\"")
    mount.readOnly is false
  }
}

object PathPatterns {
  lazy val NoSlashesPattern: Regex = """^[^/]*$""".r
  lazy val AbsolutePathPattern: Regex = """^/[^/].*$""".r
  lazy val DotPaths: Seq[String] = Seq(".", "..")
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
    info.options is validOptions
  }

  def fromProto(evi: Protos.Volume.ExternalVolumeInfo): ExternalVolumeInfo =
    ExternalVolumeInfo(
      if (evi.hasSize) Some(evi.getSize) else None,
      evi.getName,
      evi.getProvider,
      evi.getOptionsList.map { p => p.getKey -> p.getValue }(collection.breakOut)
    )
}

case class ExternalVolume(name: Option[String], external: ExternalVolumeInfo)
  extends Volume

object ExternalVolume {
  def validExternalVolume(enabledFeatures: Set[String]): Validator[ExternalVolume] =
    validator[ExternalVolume] { ev =>
      ev is featureEnabled(enabledFeatures, Features.EXTERNAL_VOLUMES)
      ev.name is optional(notEmpty)
      ev.external is ExternalVolumeInfo.validExternalVolumeInfo
    } and ExternalVolumes.validExternalVolume
}

/**
  * A volume referring to an existing secret.
  */
case class SecretVolume(name: Option[String], secret: String)
  extends Volume with plugin.SecretVolumeSpec

/**
  * Ephemeral volumes share the lifetime of the pod instance they're used within and serve to
  * provide temporary "scratch" space that sub-containers may use to share files, sockets, etc.
  */
case class EphemeralVolume(name: Option[String])
  extends Volume
