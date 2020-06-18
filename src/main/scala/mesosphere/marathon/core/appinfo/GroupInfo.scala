package mesosphere.marathon
package core.appinfo

object GroupInfo {
  sealed trait Embed
  object Embed {
    case object Groups extends Embed
    case object Apps extends Embed
    case object Pods extends Embed
  }
}
