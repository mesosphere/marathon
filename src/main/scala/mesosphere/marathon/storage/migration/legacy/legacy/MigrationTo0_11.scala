package mesosphere.marathon.storage.migration.legacy.legacy

import akka.stream.Materializer
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, Timestamp, VersionInfo }
import mesosphere.marathon.storage.LegacyStorageConfig
import mesosphere.marathon.storage.repository.{ AppRepository, GroupRepository, PodRepository }
import mesosphere.marathon.stream.Sink
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Implements the following migration logic:
  * * Add version info to the AppDefinition by looking at all saved versions.
  * * Make the groupRepository the ultimate source of truth for the latest app version.
  */
@SuppressWarnings(Array("ClassNames"))
class MigrationTo0_11(legacyConfig: Option[LegacyStorageConfig])(implicit
  ctx: ExecutionContext,
    mat: Materializer,
    metrics: Metrics) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  def migrateApps(): Future[Unit] = {
    legacyConfig.fold {
      log.info("Skipped 0.11 migration, not a legacy store")
      Future.successful(())
    } { config =>
      log.info("Start 0.11 migration")

      val appRepository = AppRepository.legacyRepository(config.entityStore[AppDefinition], config.maxVersions)
      val podRepository = PodRepository.legacyRepository(config.entityStore[PodDefinition], config.maxVersions)
      val groupRepository =
        GroupRepository.legacyRepository(config.entityStore[Group], config.maxVersions, appRepository, podRepository)
      val rootGroupFuture = groupRepository.root()
      val appIdsFuture = appRepository.ids()
      for {
        rootGroup <- rootGroupFuture
        appIdsFromAppRepo <- appIdsFuture.runWith(Sink.set)
        appIds = appIdsFromAppRepo ++ rootGroup.transitiveAppIds
        _ = log.info(s"Discovered ${appIds.size} app IDs")
        appsWithVersions <- processApps(appRepository, appIds, rootGroup)
        _ <- storeUpdatedAppsInRootGroup(groupRepository, rootGroup, appsWithVersions)
      } yield log.info("Finished 0.11 migration")
    }
  }

  private[this] def storeUpdatedAppsInRootGroup(
    groupRepository: GroupRepository,
    rootGroup: Group,
    updatedApps: Iterable[AppDefinition]): Future[Unit] = {
    val updatedGroup = updatedApps.foldLeft(rootGroup){ (updatedGroup, updatedApp) =>
      updatedGroup.updateApp(updatedApp.id, _ => updatedApp, updatedApp.version)
    }
    groupRepository.storeRoot(updatedGroup, Nil, Nil, Nil, Nil).map(_ => ())
  }

  private[this] def processApps(
    appRepository: AppRepository,
    appIds: Iterable[PathId], rootGroup: Group): Future[Vector[AppDefinition]] = {
    appIds.foldLeft(Future.successful[Vector[AppDefinition]](Vector.empty)) { (otherStores, appId) =>
      otherStores.flatMap { storedApps =>
        val maybeAppInGroup = rootGroup.app(appId)
        maybeAppInGroup match {
          case Some(appInGroup) =>
            addVersionInfo(appRepository, appId, appInGroup).map(storedApps ++ _)
          case None =>
            log.warn(s"App [$appId] will be expunged because it is not contained in the group data")
            appRepository.delete(appId).map(_ => storedApps)
        }
      }
    }
  }

  private[this] def addVersionInfo(
    appRepository: AppRepository,
    id: PathId, appInGroup: AppDefinition): Future[Option[AppDefinition]] = {
    def addVersionInfoToVersioned(
      maybeLastApp: Option[AppDefinition],
      maybeNextApp: Option[AppDefinition]): Option[AppDefinition] = {
      maybeNextApp.map { nextApp =>
        maybeLastApp match {
          case Some(lastApp) if !lastApp.isUpgrade(nextApp) =>
            log.info(s"Adding versionInfo to ${nextApp.id} (${nextApp.version}): scaling or restart")
            nextApp.copy(versionInfo = lastApp.versionInfo.withScaleOrRestartChange(nextApp.version))
          case _ =>
            log.info(s"Adding versionInfo to ${nextApp.id} (${nextApp.version}): new config")
            nextApp.copy(versionInfo = VersionInfo.forNewConfig(nextApp.version))
        }
      }
    }

    def loadApp(id: PathId, version: Timestamp): Future[Option[AppDefinition]] = {
      if (appInGroup.version == version) {
        Future.successful(Some(appInGroup))
      } else {
        appRepository.getVersion(id, version.toOffsetDateTime)
      }
    }

    val sortedVersions = appRepository.versions(id).map(Timestamp(_)).runWith(Sink.sortedSet)
    sortedVersions.flatMap { sortedVersionsWithoutGroup =>
      val sortedVersions = sortedVersionsWithoutGroup ++ Seq(appInGroup.version)
      log.info(s"Add versionInfo to app [$id] for ${sortedVersions.size} versions")

      sortedVersions.foldLeft(Future.successful[Option[AppDefinition]](None)) { (maybeLastAppFuture, nextVersion) =>
        for {
          maybeLastApp <- maybeLastAppFuture
          maybeNextApp <- loadApp(id, nextVersion)
          withVersionInfo = addVersionInfoToVersioned(maybeLastApp, maybeNextApp)
          storedResult <- withVersionInfo
            .fold(maybeLastAppFuture)((newApp: AppDefinition) => appRepository.store(newApp).map(_ => Some(newApp)))
        } yield storedResult
      }
    }
  }
}
