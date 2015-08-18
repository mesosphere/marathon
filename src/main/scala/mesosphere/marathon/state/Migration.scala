package mesosphere.marathon.state

import java.io.{ ByteArrayInputStream, ObjectInputStream }
import javax.inject.Inject

import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.StorageVersions._
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.tasks.TaskTracker.InternalApp
import mesosphere.marathon.{ BuildInfo, MarathonConf }
import mesosphere.util.Logging
import mesosphere.util.ThreadPoolContext.context
import mesosphere.util.state.{ PersistentStoreManagement, PersistentEntity, PersistentStore }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.SortedSet
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.{ Failure, Success }

class Migration @Inject() (
    store: PersistentStore,
    appRepo: AppRepository,
    groupRepo: GroupRepository,
    config: MarathonConf,
    metrics: Metrics) extends Logging {

  //scalastyle:off magic.number

  type MigrationAction = (StorageVersion, () => Future[Any])

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
      for {
        _ <- new AddVersionInfoToAppsMigration(appRepo).migrateApps()
        _ <- putAppsIntoGroup()
      } yield ()
    }
  )

  def applyMigrationSteps(from: StorageVersion): Future[List[StorageVersion]] = {
    val result = migrations.filter(_._1 > from).sortBy(_._1).map {
      case (migrateVersion, change) =>
        log.info(
          s"Migration for storage: ${from.str} to current: ${current.str}: " +
            s"apply change for version: ${migrateVersion.str} "
        )
        change.apply().map(_ => migrateVersion)
    }
    Future.sequence(result)
  }

  def initializeStore(): Future[Unit] = store match {
    case manager: PersistentStoreManagement => manager.initialize()
    case _: PersistentStore                 => Future.successful(())
  }

  def migrate(): StorageVersion = {
    val result = for {
      _ <- initializeStore()
      changes <- currentStorageVersion.flatMap(applyMigrationSteps)
      storedVersion <- storeCurrentVersion
    } yield storedVersion

    result.onComplete {
      case Success(version) => log.info(s"Migration successfully applied for version ${version.str}")
      case Failure(ex)      => log.error("Migration failed!", ex)
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
    val taskTracker = new TaskTracker(store, config, metrics)
    def fetchApp(appId: PathId): Option[InternalApp] = {
      Await.result(store.load("tasks:" + appId.safePath), config.zkTimeoutDuration).map { entity =>
        val source = new ObjectInputStream(new ByteArrayInputStream(entity.bytes.toArray))
        val fetchedTasks = taskTracker.legacyDeserialize(appId, source).map {
          case (key, task) =>
            val builder = task.toBuilder.clearOBSOLETEStatuses()
            task.getOBSOLETEStatusesList.asScala.lastOption.foreach(builder.setStatus)
            key -> builder.build()
        }
        new InternalApp(appId, fetchedTasks, false)
      }
    }
    def storeApp(app: InternalApp): Future[Seq[PersistentEntity]] = {
      Future.sequence(app.tasks.values.toSeq.map(taskTracker.store(app.appName, _)))
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

class AddVersionInfoToAppsMigration(appRepository: AppRepository) {
  private[this] val log = LoggerFactory.getLogger(getClass)

  def migrateApps(): Future[Unit] = {
    val appIds = appRepository.allPathIds()

    appIds.flatMap { appIds =>
      appIds.foldLeft(Future.successful(())) { (otherStores, appId) =>
        addVersionInfo(appId).map(_ => ())
      }
    }
  }

  private[this] def addVersionInfo(id: PathId): Future[Option[AppDefinition]] = {
    def addVersionInfoToVersioned(
      maybeLastApp: Option[AppDefinition],
      nextVersion: Timestamp,
      maybeNextApp: Option[AppDefinition]): Option[AppDefinition] =
      {
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

    val sortedVersions = appRepository.listVersions(id).map(_.toSeq.sorted)
    sortedVersions.flatMap { sortedVersions =>
      sortedVersions.foldLeft(Future.successful[Option[AppDefinition]](None)) { (maybeLastAppFuture, nextVersion) =>
        for {
          maybeLastApp <- maybeLastAppFuture
          maybeNextApp <- appRepository.app(id, nextVersion)
          withVersionInfo = addVersionInfoToVersioned(maybeLastApp, nextVersion, maybeNextApp)
          storedResult <- withVersionInfo
            .map((newApp: AppDefinition) => appRepository.store(newApp).map(Some(_)))
            .getOrElse(maybeLastAppFuture)
        } yield storedResult
      }
    }

  }
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
  }

  def empty: StorageVersion = StorageVersions(0, 0, 0)
}
