package mesosphere.marathon
package core.appinfo.impl

import mesosphere.marathon.core.appinfo.AppInfo.Embed
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.ReadOnlyAppRepository
import mesosphere.marathon.stream._
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.Future

private[appinfo] class DefaultInfoService(
    groupManager: GroupManager,
    appRepository: ReadOnlyAppRepository,
    newBaseData: () => AppInfoBaseData) extends AppInfoService with GroupInfoService {
  import scala.concurrent.ExecutionContext.Implicits.global

  private[this] val log = LoggerFactory.getLogger(getClass)

  override def selectApp(id: PathId, selector: AppSelector, embed: Set[AppInfo.Embed]): Future[Option[AppInfo]] = {
    log.debug(s"queryForAppId $id")
    appRepository.get(id).flatMap {
      case Some(app) if selector.matches(app) => newBaseData().appInfoFuture(app, embed).map(Some(_))
      case None => Future.successful(None)
    }
  }

  override def selectAppsBy(selector: AppSelector, embed: Set[AppInfo.Embed]): Future[Seq[AppInfo]] = {
    log.debug("queryAll")
    groupManager.rootGroup()
      .map(_.transitiveApps.filter(selector.matches))
      .flatMap(apps => resolveAppInfos(apps.to[Seq], embed))
  }

  override def selectAppsInGroup(groupId: PathId, selector: AppSelector,
    embed: Set[AppInfo.Embed]): Future[Seq[AppInfo]] = {
    log.debug(s"queryAllInGroup $groupId")
    groupManager
      .group(groupId)
      .map(_.map(_.transitiveApps.filterAs(selector.matches)(collection.breakOut)).getOrElse(Seq.empty))
      .flatMap(apps => resolveAppInfos(apps, embed))
  }

  override def selectGroup(groupId: PathId, groupSelector: GroupSelector,
    appEmbed: Set[Embed], groupEmbed: Set[GroupInfo.Embed]): Future[Option[GroupInfo]] = {
    groupManager.group(groupId).flatMap {
      case Some(group) => queryForGroup(group, groupSelector, appEmbed, groupEmbed)
      case None => Future.successful(None)
    }
  }

  override def selectGroupVersion(groupId: PathId, version: Timestamp, groupSelector: GroupSelector,
    groupEmbed: Set[GroupInfo.Embed]): Future[Option[GroupInfo]] = {
    groupManager.group(groupId, version).flatMap {
      case Some(group) => queryForGroup(group, groupSelector, Set.empty, groupEmbed)
      case None => Future.successful(None)
    }
  }

  private[this] def queryForGroup(
    group: Group,
    groupSelector: GroupSelector,
    appEmbed: Set[AppInfo.Embed],
    groupEmbed: Set[GroupInfo.Embed]): Future[Option[GroupInfo]] = {

    //fetch all transitive app infos with one request
    val appInfos = {
      if (groupEmbed(GroupInfo.Embed.Apps))
        resolveAppInfos(group.transitiveApps.filterAs(groupSelector.matches)(collection.breakOut), appEmbed)
      else
        Future.successful(Seq.empty)
    }

    appInfos.map { apps =>
      val infoById: Map[PathId, AppInfo] = apps.map(info => info.app.id -> info)(collection.breakOut)
      //already matched groups are stored here for performance reasons (match only once)
      val alreadyMatched = mutable.Map.empty[PathId, Boolean]
      def queryGroup(ref: Group): Option[GroupInfo] = {
        def groups: Option[Seq[GroupInfo]] =
          if (groupEmbed(GroupInfo.Embed.Groups))
            Some(ref.groups.toIndexedSeq.flatMap(queryGroup).sortBy(_.group.id))
          else
            None
        def apps: Option[Seq[AppInfo]] =
          if (groupEmbed(GroupInfo.Embed.Apps))
            Some(ref.apps.keys.flatMap(infoById.get)(collection.breakOut).sortBy(_.app.id))
          else
            None
        //if a subgroup is allowed, we also have to allow all parents implicitly
        def groupMatches(group: Group): Boolean = {
          alreadyMatched.getOrElseUpdate(group.id, groupSelector.matches(group) ||
            group.groups.exists(groupMatches))
        }
        if (groupMatches(ref)) Some(GroupInfo(ref, apps, groups)) else None
      }
      queryGroup(group)
    }
  }

  private[this] def resolveAppInfos(apps: Seq[AppDefinition], embed: Set[AppInfo.Embed]): Future[Seq[AppInfo]] = {
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
