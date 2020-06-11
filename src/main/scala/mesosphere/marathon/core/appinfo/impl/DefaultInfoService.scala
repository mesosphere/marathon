package mesosphere.marathon
package core.appinfo.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.appinfo.AppInfo.Embed
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.raml.PodStatus
import mesosphere.marathon.state._

import scala.async.Async.{async, await}
import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

private[appinfo] class DefaultInfoService(
    groupManager: GroupManager,
    newBaseData: () => AppInfoBaseData)(implicit ec: ExecutionContext)
  extends AppInfoService with GroupInfoService with PodStatusService with StrictLogging {

  override def selectPodStatus(id: AbsolutePathId, selector: PodSelector): Future[Option[PodStatus]] =
    async { // linter:ignore UnnecessaryElseBranch
      logger.debug(s"query for pod $id")
      val maybePod = groupManager.pod(id)
      maybePod.filter(selector.matches) match {
        case Some(pod) => Some(await(newBaseData().podStatus(pod)))
        case None => Option.empty[PodStatus]
      }
    }

  override def selectPodStatuses(ids: Set[AbsolutePathId], selector: PodSelector): Future[Seq[PodStatus]] = {
    val baseData = newBaseData()

    val pods = ids.toVector.flatMap(groupManager.pod(_)).filter(selector.matches)
    resolvePodInfos(pods, baseData)
  }

  override def selectApp(id: AbsolutePathId, selector: AppSelector, embed: Set[AppInfo.Embed]): Future[Option[raml.AppInfo]] = {
    logger.debug(s"queryForAppId $id")
    groupManager.app(id) match {
      case Some(app) if selector.matches(app) => newBaseData().appInfoFuture(app, embed).map(Some(_))
      case None => Future.successful(None)
    }
  }

  override def selectAppsBy(selector: AppSelector, embed: Set[AppInfo.Embed]): Future[Seq[raml.AppInfo]] =
    async { // linter:ignore UnnecessaryElseBranch
      logger.debug("queryAll")
      val rootGroup = groupManager.rootGroup()
      val selectedApps: IndexedSeq[AppDefinition] = rootGroup.transitiveApps.iterator.filter(selector.matches).to(IndexedSeq)
      val infos = await(resolveAppInfos(selectedApps, embed))
      infos
    }

  override def selectAppsInGroup(groupId: AbsolutePathId, selector: AppSelector,
    embed: Set[AppInfo.Embed]): Future[Seq[raml.AppInfo]] =

    async { // linter:ignore UnnecessaryElseBranch
      logger.debug(s"queryAllInGroup $groupId")
      val maybeGroup: Option[Group] = groupManager.group(groupId)
      val maybeApps: Option[IndexedSeq[AppDefinition]] =
        maybeGroup.map(_.transitiveApps.iterator.filter(selector.matches).to(IndexedSeq))
      maybeApps match {
        case Some(selectedApps) => await(resolveAppInfos(selectedApps, embed))
        case None => Seq.empty
      }
    }

  override def selectGroup(groupId: AbsolutePathId, selectors: GroupInfoService.Selectors,
    appEmbed: Set[Embed], groupEmbed: Set[GroupInfo.Embed]): Future[Option[raml.GroupInfo]] = {
    groupManager.group(groupId) match {
      case Some(group) => queryForGroup(group, selectors, appEmbed, groupEmbed)
      case None => Future.successful(None)
    }
  }

  override def selectGroupVersion(groupId: AbsolutePathId, version: Timestamp, selectors: GroupInfoService.Selectors,
    groupEmbed: Set[GroupInfo.Embed]): Future[Option[raml.GroupInfo]] = {
    groupManager.group(groupId, version).flatMap {
      case Some(group) => queryForGroup(group, selectors, Set.empty, groupEmbed)
      case None => Future.successful(None)
    }
  }

  private case class LazyCell[T](evalution: () => T) { lazy val value = evalution() }

  private[this] def queryForGroup(
    group: Group,
    selectors: GroupInfoService.Selectors,
    appEmbed: Set[AppInfo.Embed],
    groupEmbed: Set[GroupInfo.Embed]): Future[Option[raml.GroupInfo]] =

    async { // linter:ignore UnnecessaryElseBranch
      val cachedBaseData = LazyCell(() => newBaseData()) // Work around strange async/eval compile bug in Scala 2.12

      val groupEmbedApps = groupEmbed(GroupInfo.Embed.Apps)
      val groupEmbedPods = groupEmbed(GroupInfo.Embed.Pods)

      //fetch all transitive app infos and pod statuses with one request
      val infoById: Map[AbsolutePathId, raml.AppInfo] =
        if (groupEmbedApps) {
          val filteredApps: IndexedSeq[AppDefinition] =
            group.transitiveApps.iterator.filter(selectors.appSelector.matches).to(IndexedSeq)
          await(resolveAppInfos(filteredApps, appEmbed, cachedBaseData.value)).iterator.map { info =>
            AbsolutePathId(info.id) -> info
          }.toMap
        } else {
          Map.empty[AbsolutePathId, raml.AppInfo]
        }

      val statusById: Map[AbsolutePathId, PodStatus] =
        if (groupEmbedPods) {
          val filteredPods: IndexedSeq[PodDefinition] =
            group.transitivePods.iterator.filter(selectors.podSelector.matches).to(IndexedSeq)
          await(resolvePodInfos(filteredPods, cachedBaseData.value)).iterator.map { status =>
            AbsolutePathId(status.id) -> status
          }.toMap
        } else {
          Map.empty[AbsolutePathId, PodStatus]
        }

      //already matched groups are stored here for performance reasons (match only once)
      val alreadyMatched = mutable.Map.empty[PathId, Boolean]
      def queryGroup(ref: Group): Option[raml.GroupInfo] = {
        //if a subgroup is allowed, we also have to allow all parents implicitly
        def groupMatches(group: Group): Boolean = {
          alreadyMatched.getOrElseUpdate(
            group.id,
            selectors.groupSelector.matches(group) ||
              group.groupsById.exists { case (_, group) => groupMatches(group) } ||
              group.apps.keys.exists(infoById.contains)) || group.pods.keys.exists(statusById.contains)
        }
        if (groupMatches(ref)) {
          val groups: Set[raml.GroupInfo] =
            if (groupEmbed(GroupInfo.Embed.Groups))
              ref.groupsById.values.flatMap(queryGroup).toSet
            else
              Set.empty
          val apps: Set[raml.AppInfo] =
            if (groupEmbedApps)
              ref.apps.keys.flatMap(infoById.get).toSet
            else
              Set.empty
          val pods: Set[PodStatus] =
            if (groupEmbedPods)
              ref.pods.keys.iterator.flatMap(statusById.get).toSeq.toSet
            else
              Set.empty

          Some(raml.GroupInfo(
            id = ref.id.toString,
            apps = apps,
            pods = pods,
            groups = groups,
            dependencies = ref.dependencies.map(_.toString),
            version = Some(ref.version.toOffsetDateTime),
            enforceRole = Some(ref.enforceRole)))
        } else None
      }
      queryGroup(group)
    }

  private[this] def resolveAppInfos(
    specs: Seq[RunSpec],
    embed: Set[AppInfo.Embed],
    baseData: AppInfoBaseData = newBaseData()): Future[Seq[raml.AppInfo]] = Future.sequence(specs.collect {
    case app: AppDefinition =>
      baseData.appInfoFuture(app, embed)
  })

  private[this] def resolvePodInfos(
    specs: Seq[RunSpec],
    baseData: AppInfoBaseData): Future[Seq[PodStatus]] = Future.sequence(specs.collect {
    case pod: PodDefinition =>
      baseData.podStatus(pod)
  })
}
