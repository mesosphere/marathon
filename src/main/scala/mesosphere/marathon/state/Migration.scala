package mesosphere.marathon.state

import java.io.{ ByteArrayInputStream, ObjectInputStream }
import javax.inject.Inject

import mesosphere.marathon.Protos.{ MarathonApp, MarathonTask, StorageVersion }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.StorageVersions._
import mesosphere.marathon.tasks.TaskTrackerImpl.InternalApp
import mesosphere.marathon.{ BuildInfo, MarathonConf, MigrationFailedException }
import mesosphere.util.Logging
import mesosphere.util.ThreadPoolContext.context
import mesosphere.util.state.{ PersistentStore, PersistentStoreManagement }
import org.apache.log4j.Logger
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.SortedSet
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal

class Migration @Inject() (
    store: PersistentStore,
    appRepo: AppRepository,
    groupRepo: GroupRepository,
    taskRepo: TaskRepository,
    config: MarathonConf,
    metrics: Metrics) extends Logging {

  //scalastyle:off magic.number

  type MigrationAction = (StorageVersion, () => Future[Any])

  private[state] val minSupportedStorageVersion = StorageVersions(0, 3, 0)

  /**
    * All the migrations, that have to be applied.
    * They get applied after the master has been elected.
    */
  def migrations: List[MigrationAction] = List(
    StorageVersions(0, 5, 0) -> { () =>
      changeApps(app => app.copy(id = app.id.toString.toLowerCase.replaceAll("_", "-").toRootPath))
    },
    StorageVersions(0, 7, 0) -> { () =>
      for {
        _ <- changeTasks(app => new InternalApp(app.appName.canonicalPath(), app.tasks, app.shutdown))
        _ <- changeApps(app => app.copy(id = app.id.canonicalPath()))
        _ <- putAppsIntoGroup()
      } yield ()
    },
    StorageVersions(0, 11, 0) -> { () =>
      new MigrationTo0_11(groupRepo, appRepo).migrateApps().recover {
        case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 0.11", e)
      }
    },
    StorageVersions(0, 13, 0) -> { () =>
      new MigrationTo0_13(taskRepo, store).migrate().recover {
        case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 0.13", e)
      }
    }
  )

  def applyMigrationSteps(from: StorageVersion): Future[List[StorageVersion]] = {
    if (from < minSupportedStorageVersion && from.nonEmpty) {
      val msg = s"Migration from versions < $minSupportedStorageVersion is not supported. Your version: $from"
      throw new MigrationFailedException(msg)
    }
    migrations.filter(_._1 > from).sortBy(_._1).foldLeft(Future.successful(List.empty[StorageVersion])) {
      case (resultsFuture, (migrateVersion, change)) => resultsFuture.flatMap { res =>
        log.info(
          s"Migration for storage: ${from.str} to current: ${current.str}: " +
            s"apply change for version: ${migrateVersion.str} "
        )
        change.apply().map(_ => res :+ migrateVersion)
      }
    }
  }

  def initializeStore(): Future[Unit] = store match {
    case manager: PersistentStoreManagement => manager.initialize()
    case _: PersistentStore                 => Future.successful(())
  }

  def migrate(): StorageVersion = {
    val versionFuture = for {
      _ <- initializeStore()
      changes <- currentStorageVersion.flatMap(applyMigrationSteps)
      storedVersion <- storeCurrentVersion
    } yield storedVersion

    val result = versionFuture.map { version =>
      log.info(s"Migration successfully applied for version ${version.str}")
      version
    }.recover {
      case ex: MigrationFailedException => throw ex
      case NonFatal(ex)                 => throw new MigrationFailedException("MigrationFailed", ex)
    }

    Await.result(result, Duration.Inf)
  }

  private val storageVersionName = "internal:storage:version"

  def currentStorageVersion: Future[StorageVersion] = {
    store.load(storageVersionName).map {
      case Some(variable) => StorageVersion.parseFrom(variable.bytes.toArray)
      case None           => StorageVersions.empty
    }
  }

  def storeCurrentVersion: Future[StorageVersion] = {
    val bytes = StorageVersions.current.toByteArray
    store.load(storageVersionName).flatMap {
      case Some(entity) => store.update(entity.withNewContent(bytes))
      case None         => store.create(storageVersionName, bytes)
    }.map{ _ => StorageVersions.current }
  }

  // specific migration helper methods

  private def changeApps(fn: AppDefinition => AppDefinition): Future[Any] = {
    appRepo.apps().flatMap { apps =>
      val mappedApps = apps.map { app => appRepo.store(fn(app)) }
      Future.sequence(mappedApps)
    }
  }

  private def changeTasks(fn: InternalApp => InternalApp): Future[Any] = {
    val LEGACY_PREFIX = "tasks:"
    def fetchApp(appId: PathId): Option[InternalApp] = {
      Await.result(store.load(LEGACY_PREFIX + appId.safePath), config.zkTimeoutDuration).map { entity =>
        val source = new ObjectInputStream(new ByteArrayInputStream(entity.bytes.toArray))
        val fetchedTasks = LegacyTaskMigrationHelper.legacyDeserialize(appId, source).map {
          case (key, task) =>
            val builder = task.toBuilder.clearOBSOLETEStatuses()
            task.getOBSOLETEStatusesList.asScala.lastOption.foreach(builder.setStatus)
            key -> builder.build()
        }
        new InternalApp(appId, fetchedTasks, false)
      }
    }
    def storeApp(app: InternalApp): Future[Seq[MarathonTask]] = {
      Future.sequence(app.tasks.values.toSeq.map(taskRepo.store))
    }
    appRepo.allPathIds().flatMap { apps =>
      val res = apps.flatMap(fetchApp).map{ app => storeApp(fn(app)) }
      Future.sequence(res)
    }
  }

  private def putAppsIntoGroup(): Future[Any] = {
    groupRepo.group("root").map(_.getOrElse(Group.empty)).map { group =>
      appRepo.apps().flatMap { apps =>
        val updatedGroup = apps.foldLeft(group) { (group, app) =>
          val updatedApp = app.copy(id = app.id.canonicalPath())
          group.updateApp(updatedApp.id, _ => updatedApp, Timestamp.now())
        }
        groupRepo.store("root", updatedGroup)
      }
    }
  }
}

object LegacyTaskMigrationHelper {

  private[this] val log = Logger.getLogger(getClass.getName)

  def legacyDeserialize(appId: PathId, source: ObjectInputStream): TrieMap[String, MarathonTask] = {
    val results = TrieMap[String, MarathonTask]()

    if (source.available > 0) {
      try {
        val size = source.readInt
        val bytes = new Array[Byte](size)
        source.readFully(bytes)
        val app = MarathonApp.parseFrom(bytes)
        if (app.getName != appId.toString) {
          log.warn(s"App name from task state for $appId is wrong!  Got '${app.getName}' Continuing anyway...")
        }
        results ++= app.getTasksList.asScala.map(x => x.getId -> x)
      }
      catch {
        case e: com.google.protobuf.InvalidProtocolBufferException =>
          log.warn(s"Unable to deserialize task state for $appId", e)
      }
    }
    else {
      log.warn(s"Unable to deserialize task state for $appId")
    }
    results
  }

}

/**
  * Implements the following migration logic:
  * * Add version info to the AppDefinition by looking at all saved versions.
  * * Make the groupRepository the ultimate source of truth for the latest app version.
  */
class MigrationTo0_11(groupRepository: GroupRepository, appRepository: AppRepository) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  def migrateApps(): Future[Unit] = {
    log.info("Start 0.11 migration")
    val rootGroupFuture = groupRepository.rootGroup().map(_.getOrElse(Group.empty))
    val appIdsFuture = appRepository.allPathIds()

    for {
      rootGroup <- rootGroupFuture
      appIdsFromAppRepo <- appIdsFuture
      appIds = appIdsFromAppRepo.toSet ++ rootGroup.transitiveApps.map(_.id)
      _ = log.info(s"Discovered ${appIds.size} app IDs")
      appsWithVersions <- processApps(appIds, rootGroup)
      _ <- storeUpdatedAppsInRootGroup(rootGroup, appsWithVersions)
    } yield log.info("Finished 0.11 migration")
  }

  private[this] def storeUpdatedAppsInRootGroup(
    rootGroup: Group,
    updatedApps: Iterable[AppDefinition]): Future[Unit] = {
    val updatedGroup = updatedApps.foldLeft(rootGroup){ (updatedGroup, updatedApp) =>
      updatedGroup.updateApp(updatedApp.id, _ => updatedApp, updatedApp.version)
    }
    groupRepository.store(groupRepository.zkRootName, updatedGroup).map(_ => ())
  }

  private[this] def processApps(appIds: Iterable[PathId], rootGroup: Group): Future[Vector[AppDefinition]] = {
    appIds.foldLeft(Future.successful[Vector[AppDefinition]](Vector.empty)) { (otherStores, appId) =>
      otherStores.flatMap { storedApps =>
        val maybeAppInGroup = rootGroup.app(appId)
        maybeAppInGroup match {
          case Some(appInGroup) =>
            addVersionInfo(appId, appInGroup).map(storedApps ++ _)
          case None =>
            log.warn(s"App [$appId] will be expunged because it is not contained in the group data")
            appRepository.expunge(appId).map(_ => storedApps)
        }
      }
    }
  }

  private[this] def addVersionInfo(id: PathId, appInGroup: AppDefinition): Future[Option[AppDefinition]] = {
    def addVersionInfoToVersioned(
      maybeLastApp: Option[AppDefinition],
      nextVersion: Timestamp,
      maybeNextApp: Option[AppDefinition]): Option[AppDefinition] = {
      maybeNextApp.map { nextApp =>
        maybeLastApp match {
          case Some(lastApp) if !lastApp.isUpgrade(nextApp) =>
            log.info(s"Adding versionInfo to ${nextApp.id} (${nextApp.version}): scaling or restart")
            nextApp.copy(versionInfo = lastApp.versionInfo.withScaleOrRestartChange(nextApp.version))
          case _ =>
            log.info(s"Adding versionInfo to ${nextApp.id} (${nextApp.version}): new config")
            nextApp.copy(versionInfo = AppDefinition.VersionInfo.forNewConfig(nextApp.version))
        }
      }
    }

    def loadApp(id: PathId, version: Timestamp): Future[Option[AppDefinition]] = {
      if (appInGroup.version == version) {
        Future.successful(Some(appInGroup))
      }
      else {
        appRepository.app(id, version)
      }
    }

    val sortedVersions = appRepository.listVersions(id).map(_.to[SortedSet])
    sortedVersions.flatMap { sortedVersionsWithoutGroup =>
      val sortedVersions = sortedVersionsWithoutGroup ++ Seq(appInGroup.version)
      log.info(s"Add versionInfo to app [$id] for ${sortedVersions.size} versions")

      sortedVersions.foldLeft(Future.successful[Option[AppDefinition]](None)) { (maybeLastAppFuture, nextVersion) =>
        for {
          maybeLastApp <- maybeLastAppFuture
          maybeNextApp <- loadApp(id, nextVersion)
          withVersionInfo = addVersionInfoToVersioned(maybeLastApp, nextVersion, maybeNextApp)
          storedResult <- withVersionInfo
            .map((newApp: AppDefinition) => appRepository.store(newApp).map(Some(_)))
            .getOrElse(maybeLastAppFuture)
        } yield storedResult
      }
    }

  }
}

class MigrationTo0_13(taskRepository: TaskRepository, store: PersistentStore) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  val entityStore = taskRepository.store

  // the bytes stored via TaskTracker are incompatible to EntityRepo, so we have to parse them 'manually'
  def fetchLegacyTask(taskKey: String): Future[Option[MarathonTask]] = {
    def deserialize(taskKey: String, source: ObjectInputStream): Option[MarathonTask] = {
      if (source.available > 0) {
        try {
          val size = source.readInt
          val bytes = new Array[Byte](size)
          source.readFully(bytes)
          Some(MarathonTask.parseFrom(bytes))
        }
        catch {
          case e: com.google.protobuf.InvalidProtocolBufferException =>
            None
        }
      }
      else {
        None
      }
    }

    store.load("task:" + taskKey).map(_.flatMap { entity =>
      val source = new ObjectInputStream(new ByteArrayInputStream(entity.bytes.toArray))
      deserialize(taskKey, source)
    })
  }

  def migrateTasks(): Future[Unit] = {
    log.info("Start 0.13 migration")

    // including 0.12, task keys are in format task:app:app.uuid – the taskId is
    // already contained the appId. When using the generic EntityRepo, a colon
    // in the key after the prefix implicitly denotes a versioned entry, so this
    // had to be changed, even though tasks are not stored with versions.
    def migrateKey(legacyKey: String): Future[Unit] = {
      fetchLegacyTask(legacyKey).flatMap {
        case Some(task) => taskRepository.store(task).flatMap { _ =>
          entityStore.expunge(legacyKey).map(_ => ())
        }
        case _ => Future.failed[Unit](new RuntimeException(s"Unable to load entity with key = $legacyKey"))
      }
    }

    entityStore.names().flatMap { keys =>
      log.info("Found {} tasks to migrate", keys.size)
      Future.sequence(keys.map(migrateKey)).map(_ => ())
    }.map { _ =>
      log.info("Completed 0.13 migration")
    }
  }

  def renameFrameworkId(): Future[Unit] = {
    val oldName = "frameworkId"
    val newName = "framework:id"
    def moveKey(bytes: IndexedSeq[Byte]): Future[Unit] = {
      for {
        _ <- store.create(newName, bytes)
        _ <- store.delete(oldName)
      } yield ()
    }
    store.load(oldName).flatMap {
      case Some(key) => moveKey(key.bytes)
      case None      => Future.successful(())
    }
  }

  def migrate(): Future[Unit] = for {
    _ <- migrateTasks()
    _ <- renameFrameworkId()
  } yield ()
}

object StorageVersions {
  val VersionRegex = """^(\d+)\.(\d+)\.(\d+).*""".r

  def apply(major: Int, minor: Int, patch: Int): StorageVersion = {
    StorageVersion
      .newBuilder()
      .setMajor(major)
      .setMinor(minor)
      .setPatch(patch)
      .build()
  }

  def current: StorageVersion = {
    BuildInfo.version match {
      case VersionRegex(major, minor, patch) =>
        StorageVersions(
          major.toInt,
          minor.toInt,
          patch.toInt
        )
    }
  }

  implicit class OrderedStorageVersion(val version: StorageVersion) extends AnyVal with Ordered[StorageVersion] {
    override def compare(that: StorageVersion): Int = {
      def by(left: Int, right: Int, fn: => Int): Int = if (left.compareTo(right) != 0) left.compareTo(right) else fn
      by(version.getMajor, that.getMajor, by(version.getMinor, that.getMinor, by(version.getPatch, that.getPatch, 0)))
    }

    def str: String = s"Version(${version.getMajor}, ${version.getMinor}, ${version.getPatch})"

    def nonEmpty: Boolean = !version.equals(empty)
  }

  def empty: StorageVersion = StorageVersions(0, 0, 0)
}
