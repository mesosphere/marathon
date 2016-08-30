package mesosphere.marathon.core.pod

case class Image(id: String, kind: ImageKind, forcePull: Boolean)

sealed trait ImageKind
case object AppC extends ImageKind
case object Docker extends ImageKind