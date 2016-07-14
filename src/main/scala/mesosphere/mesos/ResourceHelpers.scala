package mesosphere.mesos

import mesosphere.marathon.state.{ PersistentVolume, DiskType }
import mesosphere.mesos.protos.Resource
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Resource.DiskInfo.Source

// TODO - put this somewhere sensible
object ResourceHelpers {
  import mesosphere.marathon.api.v2.json.Formats.ConstraintFormat

  def requestedStringification(requested: Either[Double, PersistentVolume]): String = requested match {
    case Left(value) => s"disk:root:${value}"
    case Right(vol) =>
      val constraintsJson = vol.persistent.constraints.map(ConstraintFormat.writes).toList
      s"disk:${vol.persistent.`type`.toString}:${vol.persistent.size}:[${constraintsJson.mkString(",")}]"
  }

  def diskSourceStringification(sourceOpt: Option[Source]): String = {
    val diskType = DiskType.fromMesosType(sourceOpt.map(_.getType)).toString
    diskType + (sourceOpt match {
      case None =>
        ""
      case Some(source) =>
        ":" + source.getDiskPath
    })
  }

  implicit class RichDiskSource(source: Source) {
    def getDiskPath: String = {
      if (source.hasMount)
        source.getMount.getRoot
      else if (source.hasPath)
        source.getPath.getRoot
      else
        ""
    }
  }

  implicit class DiskRichResource(resource: Protos.Resource) {
    def isMountDiskResource: Boolean =
      resource.hasDisk && resource.getDisk.hasSource &&
        (resource.getDisk.getSource.getType == Source.Type.MOUNT)

    def getSourceOption: Option[Source] =
      if (resource.hasDisk && resource.getDisk.hasSource)
        Some(resource.getDisk.getSource)
      else
        None

    def getStringification: String = {
      require(resource.getName == Resource.DISK)
      /* TODO - make this match mesos stringification */
      (List(
        resource.getName,
        DiskType.fromMesosType(getDiskTypeOption).toString,
        resource.getScalar.getValue.toString) ++
        getDiskPathOption).mkString(":")
    }

    def getDiskTypeOption: Option[Source.Type] =
      resource.getSourceOption.map(_.getType)

    def getDiskPathOption: Option[String] =
      getSourceOption.map(_.getDiskPath)

    def afterAllocation(amount: Double): Option[Protos.Resource] = {
      if (resource.isMountDiskResource)
        None
      else if (amount >= resource.getScalar.getValue)
        None
      else
        Some(
          resource.toBuilder.
            setScalar(
              Protos.Value.Scalar.newBuilder.
                setValue(resource.getScalar.getValue - amount)).
              build)
    }
  }
}
