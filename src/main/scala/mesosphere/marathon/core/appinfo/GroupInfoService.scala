package mesosphere.marathon.core.appinfo

import mesosphere.marathon.state.{ Timestamp, PathId }

import scala.concurrent.Future

/**
  * Queries for extended group information.
  */
trait GroupInfoService extends AppInfoService {

  /**
    * Query info for an existing group.
    */
  def selectGroup(groupId: PathId,
                  groupSelector: GroupSelector,
                  appEmbed: Set[AppInfo.Embed],
                  groupEmbed: Set[GroupInfo.Embed]): Future[Option[GroupInfo]]

  def selectGroupVersion(groupId: PathId,
                         version: Timestamp,
                         groupSelector: GroupSelector,
                         groupEmbed: Set[GroupInfo.Embed]): Future[Option[GroupInfo]]
}
