package mesosphere.marathon.core.appinfo.impl

import mesosphere.marathon.core.appinfo.AppInfo.Embed
import mesosphere.marathon.core.appinfo.{ AppInfo, AppSelector, AppInfoService }
import mesosphere.marathon.state.{ AppDefinition, GroupManager, GroupRepository, AppRepository, PathId }

import scala.concurrent.Future

private[appinfo] class DefaultAppInfoService(
    groupManager: GroupManager,
    appRepository: AppRepository,
    newBaseData: () => AppInfoBaseData) extends AppInfoService {
  import scala.concurrent.ExecutionContext.Implicits.global

  override def queryForAppId(id: PathId, embed: Set[Embed]): Future[Option[AppInfo]] = {
    appRepository.currentVersion(id).flatMap {
      case Some(app) => newBaseData().appInfoFuture(app, embed).map(Some(_))
      case None      => Future.successful(None)
    }
  }

  override def queryAll(selector: AppSelector, embed: Set[Embed]): Future[Seq[AppInfo]] = {
    groupManager.rootGroup()
      .map(_.transitiveApps.filter(selector.matches(_)))
      .flatMap(resolveAppInfos(_, embed))
  }

  override def queryAllInGroup(groupId: PathId, embed: Set[Embed]): Future[Seq[AppInfo]] = {
    groupManager
      .group(groupId)
      .map(_.map(_.transitiveApps).getOrElse(Seq.empty))
      .flatMap(resolveAppInfos(_, embed))
  }

  private[this] def resolveAppInfos(apps: Iterable[AppDefinition], embed: Set[Embed]): Future[Seq[AppInfo]] = {
    val baseData = newBaseData()

    apps
      .foldLeft(Future.successful(Seq.newBuilder[AppInfo])) {
        case (builderFuture, app) =>
          builderFuture.flatMap { builder =>
            baseData.appInfoFuture(app, embed).map { appInfo =>
              builder += appInfo
              builder
            }
          }
      }.map(_.result())
  }
}
