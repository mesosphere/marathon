package mesosphere.marathon
package core.appinfo

import mesosphere.marathon.state.AbsolutePathId

import scala.concurrent.Future

/**
  * Queries for extended information about apps.
  */
trait AppInfoService {

  /**
    * Return the app info for the given path -- if an app for this path exists.
    *
    * @param embed specifies which fields in the resulting AppInfo s are filled.
    */
  def selectApp(appId: AbsolutePathId, selector: AppSelector, embed: Set[AppInfo.Embed]): Future[Option[raml.AppInfo]]

  /**
    * Return the app infos for all apps that are directly or indirectly contained in the
    * group with the given path.
    *
    * @param embed specifies which fields in the resulting AppInfo s are filled.
    */
  def selectAppsInGroup(groupId: AbsolutePathId, selector: AppSelector, embed: Set[AppInfo.Embed]): Future[Seq[raml.AppInfo]]

  /**
    * Return app infos for all apps.
    *
    * @param embed specifies which fields in the resulting AppInfo s are filled.
    */
  def selectAppsBy(selector: AppSelector, embed: Set[AppInfo.Embed]): Future[Seq[raml.AppInfo]]
}
