package mesosphere.marathon
package core.instance

import com.fasterxml.uuid.{ EthernetAddress, Generators }
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.state.{ PathId, PersistentVolume, VolumeMount }
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class LocalVolume(id: LocalVolumeId, persistentVolume: PersistentVolume, mount: VolumeMount)

case class LocalVolumeId(runSpecId: PathId, name: String, uuid: String) {
  import LocalVolumeId._
  lazy val idString = runSpecId.safePath + delimiter + name + delimiter + uuid

  override def toString: String = s"LocalVolume [$idString]"
}

object LocalVolumeId {
  private val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())
  private val delimiter = "#"
  private val LocalVolumeEncoderRE = s"^([^$delimiter]+)[$delimiter]([^$delimiter]+)[$delimiter]([^$delimiter]+)$$".r

  def apply(runSpecId: PathId, volume: PersistentVolume, mount: VolumeMount): LocalVolumeId = {
    val name = volume.name.getOrElse(mount.mountPath)
    LocalVolumeId(runSpecId, name, uuidGenerator.generate().toString)
  }

  def unapply(id: String): Option[(LocalVolumeId)] = id match {
    case LocalVolumeEncoderRE(runSpec, name, uuid) => Some(LocalVolumeId(PathId.fromSafePath(runSpec), name, uuid))
    case _ => None
  }

  implicit val localVolumeIdReader = (
    (__ \ "runSpecId").read[PathId] and
    (__ \ "containerPath").read[String] and
    (__ \ "uuid").read[String]
  )((id, path, uuid) => LocalVolumeId(id, path, uuid))

  implicit val localVolumeIdWriter = Writes[LocalVolumeId] { localVolumeId =>
    JsObject(Seq(
      "runSpecId" -> Json.toJson(localVolumeId.runSpecId),
      "containerPath" -> Json.toJson(localVolumeId.name),
      "uuid" -> Json.toJson(localVolumeId.uuid),
      "persistenceId" -> Json.toJson(localVolumeId.idString)
    ))
  }
}
