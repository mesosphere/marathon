package mesosphere.marathon
package core.appinfo

object AppInfo {
  sealed trait Embed
  object Embed {
    case object Tasks extends Embed
    case object Deployments extends Embed
    case object Readiness extends Embed
    case object Counts extends Embed
    case object LastTaskFailure extends Embed
    case object TaskStats extends Embed
  }
}
