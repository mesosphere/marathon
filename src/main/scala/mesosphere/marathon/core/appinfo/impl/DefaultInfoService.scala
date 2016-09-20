package mesosphere.marathon.core.appinfo.impl

import mesosphere.marathon.core.appinfo.AppInfo.Embed
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.{ PodDefinition, PodManager }
import mesosphere.marathon.raml.PodStatus
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.ReadOnlyAppRepository
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.Future

private[appinfo] class DefaultInfoService(
    groupManager: GroupManager,
    appRepository: ReadOnlyAppRepository,
    podManager: PodManager,
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
      .flatMap(resolveAppInfos(_, embed))
  }

  override def selectAppsInGroup(groupId: PathId, selector: AppSelector,
    embed: Set[AppInfo.Embed]): Future[Seq[AppInfo]] = {
    log.debug(s"queryAllInGroup $groupId")
    groupManager
      .group(groupId)
      .map(_.map(_.transitiveApps.filter(selector.matches)).getOrElse(Seq.empty))
      .flatMap(resolveAppInfos(_, embed))
  }

  override def selectGroup(groupId: PathId, selectors: GroupInfoService.Selectors,
    appEmbed: Set[Embed], groupEmbed: Set[GroupInfo.Embed]): Future[Option[GroupInfo]] = {
    groupManager.group(groupId).flatMap {
      case Some(group) => queryForGroup(group, selectors, appEmbed, groupEmbed)
      case None => Future.successful(None)
    }
  }

  override def selectGroupVersion(groupId: PathId, version: Timestamp, selectors: GroupInfoService.Selectors,
    groupEmbed: Set[GroupInfo.Embed]): Future[Option[GroupInfo]] = {
    groupManager.group(groupId, version).flatMap {
      case Some(group) => queryForGroup(group, selectors, Set.empty, groupEmbed)
      case None => Future.successful(None)
    }
  }

  private[this] def queryForGroup(
    group: Group,
    selectors: GroupInfoService.Selectors,
    appEmbed: Set[AppInfo.Embed],
    groupEmbed: Set[GroupInfo.Embed]): Future[Option[GroupInfo]] = {

    val groupEmbedApps = groupEmbed(GroupInfo.Embed.Apps)
    val groupEmbedPods = groupEmbed(GroupInfo.Embed.Pods)

    //fetch all transitive app infos and pod statuses with one request
    val futureStatuses: Future[Seq[SpecStatus]] = {
        resolveSpecInfos(group.transitiveRunSpecs.filter {
          case app: AppDefinition if groupEmbedApps => selectors.appSelector.matches(app)
          case pod: PodDefinition if groupEmbedPods => selectors.podSelector.matches(pod)
        }, appEmbed)
    }

    futureStatuses.map { specStatuses =>
      val infoById = specStatuses.collect {
        case Left(info) if groupEmbedApps => info.app.id -> info
      }.toMap

      val statusById = specStatuses.collect {
        case Right(podStatus) if groupEmbedPods => PathId(podStatus.id) -> podStatus
      }.toMap

      //already matched groups are stored here for performance reasons (match only once)
      val alreadyMatched = mutable.Map.empty[PathId, Boolean]
      def queryGroup(ref: Group): Option[GroupInfo] = {
        val groups: Option[Seq[GroupInfo]] =
          if (groupEmbed(GroupInfo.Embed.Groups))
            Some(ref.groups.toIndexedSeq.flatMap(queryGroup).sortBy(_.group.id))
          else
            None
        val apps: Option[Seq[AppInfo]] =
          if (groupEmbedApps)
            Some(ref.apps.keys.flatMap(infoById.get)(collection.breakOut).sortBy(_.app.id))
          else
            None
        val pods: Option[Seq[PodStatus]] =
          if (groupEmbedPods)
            Some(ref.pods.keys.flatMap(statusById.get)(collection.breakOut).sortBy(_.id))
          else
            None

        //if a subgroup is allowed, we also have to allow all parents implicitly
        def groupMatches(group: Group): Boolean = {
          alreadyMatched.getOrElseUpdate(group.id,
            selectors.groupSelector.matches(group) || group.groups.exists(groupMatches))
        }
        if (groupMatches(ref)) {
          Some(GroupInfo(ref, apps, pods, groups))
        }
        else None
      }
      queryGroup(group)
    }
  }

  // TODO(jdef) not a big fan of this
  type SpecStatus = Either[AppInfo, PodStatus]

  /**
    * convenience method that wraps resolveSpecInfos, should only call this if you're dealing purely with AppDefinitions
    */
  private[this] def resolveAppInfos(
    specs: Iterable[RunSpec],
    embed: Set[AppInfo.Embed]): Future[Seq[AppInfo]] = {

    resolveSpecInfos(specs, embed).map { specInfos =>
      specInfos.collect {
        case Left(appInfo) => appInfo
      }
    }
  }

  private[this] def resolveSpecInfos(
    specs: Iterable[RunSpec],
    embed: Set[AppInfo.Embed],
    baseData: AppInfoBaseData = newBaseData()): Future[Seq[SpecStatus]] = {

    specs
      .foldLeft(Future.successful(Seq.newBuilder[SpecStatus])) {
        case (builderFuture, spec) =>
          builderFuture.flatMap { builder =>
            spec match {
              case app: AppDefinition =>
                baseData.appInfoFuture(app, embed).map { appInfo =>
                  builder += Left(appInfo)
                  builder
                }
              case pod: PodDefinition =>
                // TODO(jdef) pod refactor this into AppInfoBaseData along with PodManager.status impl
                podManager.status(Map(pod.id -> pod)).map { status =>
                  builder += Right(status.head)
                  builder
                }
            }
          }
      }.map(_.result())
  }
}
