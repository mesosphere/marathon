package mesosphere.marathon.core.appinfo

import mesosphere.marathon.state.{ Timestamp, PathId }

import scala.concurrent.Future

/**
  * Queries for extended group information.
  */
trait GroupInfoService extends AppInfoService {

  import GroupInfoService._

  /**
    * Query info for an existing group.
    */
  def selectGroup(
    groupId: PathId,
    selectors: Selectors,
    appEmbed: Set[AppInfo.Embed],
    groupEmbed: Set[GroupInfo.Embed]): Future[Option[GroupInfo]]

  def selectGroupVersion(
    groupId: PathId,
    version: Timestamp,
    selectors: Selectors,
    groupEmbed: Set[GroupInfo.Embed]): Future[Option[GroupInfo]]
}

object GroupInfoService {

  case class Selectors(appSelector: AppSelector, podSelector: PodSelector, groupSelector: GroupSelector)

  object Selectors {
    val all = Selectors(Selector.all, Selector.all, Selector.all)
    val none = Selectors(Selector.none, Selector.none, Selector.none)
  }
}
