package mesosphere.marathon.core.appinfo

import mesosphere.marathon.state.Group

case class GroupInfo(group: Group,
                     maybeApps: Option[Seq[AppInfo]],
                     maybeGroups: Option[Seq[GroupInfo]]) {

  def transitiveApps: Option[Seq[AppInfo]] = this.maybeApps.map { apps =>
    apps ++ maybeGroups.map { _.flatMap(_.transitiveApps.getOrElse(Seq.empty)) }.getOrElse(Seq.empty)
  }
  def transitiveGroups: Option[Seq[GroupInfo]] = this.maybeGroups.map { groups =>
    groups ++ maybeGroups.map { _.flatMap(_.transitiveGroups.getOrElse(Seq.empty)) }.getOrElse(Seq.empty)
  }
}

object GroupInfo {
  sealed trait Embed
  object Embed {
    case object Groups extends Embed
    case object Apps extends Embed
  }
  lazy val empty: GroupInfo = GroupInfo(Group.empty, None, None)
}

