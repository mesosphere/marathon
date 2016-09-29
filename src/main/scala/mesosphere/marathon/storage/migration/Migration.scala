package mesosphere.marathon.storage.migration

import akka.Done
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.LegacyStorageConfig
import mesosphere.marathon.storage.migration.legacy.legacy.{ MigrationTo0_11, MigrationTo0_13, MigrationTo0_16, MigrationTo1_2 }
import mesosphere.marathon.storage.repository.{ AppRepository, DeploymentRepository, EventSubscribersRepository, FrameworkIdRepository, GroupRepository, TaskFailureRepository, TaskRepository }
import mesosphere.marathon.storage.repository.legacy.store.{ PersistentStore, PersistentStoreManagement }
import mesosphere.marathon.{ BuildInfo, MigrationFailedException, PrePostDriverCallback }

import scala.async.Async.{ async, await }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal
import scala.collection.immutable.Seq

/**
  * @param legacyConfig Optional configuration for the legacy store. This is used for all migrations
  *                     that do not use the new store and the underlying PersistentStore will be closed
  *                     when completed
  * @param persistenceStore Optional "new" PersistenceStore for new migrations, the repositories
  *                         are assumed to be in the new format.
  */
class Migration(
    private[migration] val legacyConfig: Option[LegacyStorageConfig],
    private[migration] val persistenceStore: Option[PersistenceStore[_, _, _]],
    private[migration] val appRepository: AppRepository,
    private[migration] val groupRepository: GroupRepository,
    private[migration] val deploymentRepository: DeploymentRepository,
    private[migration] val taskRepo: TaskRepository,
    private[migration] val taskFailureRepo: TaskFailureRepository,
    private[migration] val frameworkIdRepo: FrameworkIdRepository,
    private[migration] val eventSubscribersRepo: EventSubscribersRepository)(implicit
  mat: Materializer,
    metrics: Metrics) extends StrictLogging {

  import Migration._
  import StorageVersions._

  type MigrationAction = (StorageVersion, () => Future[Any])

  private[migration] val minSupportedStorageVersion = StorageVersions(0, 8, 0)

  private[migration] lazy val legacyStoreFuture: Future[Option[PersistentStore]] = legacyConfig.map { config =>
    val store = config.store
    store match {
      case s: PersistentStoreManagement with PrePostDriverCallback =>
        s.preDriverStarts.flatMap(_ => s.initialize()).map(_ => Some(store))
      case s: PersistentStoreManagement =>
        s.initialize().map(_ => Some(store))
      case s: PrePostDriverCallback =>
        s.preDriverStarts.map(_ => Some(store))
      case _ =>
        Future.successful(Some(store))
    }
  }.getOrElse(Future.successful(None))

  /**
    * All the migrations, that have to be applied.
    * They get applied after the master has been elected.
    */
  def migrations: List[MigrationAction] =
    List(
      StorageVersions(0, 7, 0) -> { () =>
        Future.failed(new IllegalStateException("migration from 0.7.x not supported anymore"))
      },
      StorageVersions(0, 11, 0) -> { () =>
        new MigrationTo0_11(legacyConfig).migrateApps().recover {
          case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 0.11", e)
        }
      },
      StorageVersions(0, 13, 0) -> { () =>
        new MigrationTo0_13(legacyConfig).migrate().recover {
          case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 0.13", e)
        }
      },
      StorageVersions(0, 16, 0) -> { () =>
        new MigrationTo0_16(legacyConfig).migrate().recover {
          case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 0.16", e)
        }
      },
      StorageVersions(1, 2, 0) -> { () =>
        new MigrationTo1_2(legacyConfig).migrate().recover {
          case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 1.2", e)
        }
      },
      StorageVersions(1, 4, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE) -> { () =>
        new MigrationTo1_4_PersistenceStore(this).migrate().recover {
          case NonFatal(e) => throw new MigrationFailedException("while migrating storage to 1.3", e)
        }
      }
    )

  def applyMigrationSteps(from: StorageVersion): Future[Seq[StorageVersion]] = {
    migrations.filter(_._1 > from).sortBy(_._1).foldLeft(Future.successful(Seq.empty[StorageVersion])) {
      case (resultsFuture, (migrateVersion, change)) => resultsFuture.flatMap { res =>
        logger.info(
          s"Migration for storage: ${from.str} to current: ${current.str}: " +
            s"apply change for version: ${migrateVersion.str} "
        )
        change.apply().map(_ => res :+ migrateVersion)
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  def migrate(): Seq[StorageVersion] = {
    val result = async { // linter:ignore UnnecessaryElseBranch
      val legacyStore = await(legacyStoreFuture)
      val currentVersion = await(getCurrentVersion(legacyStore))

      val currentBuildVersion = persistenceStore.fold(StorageVersions.current) { _ =>
        StorageVersions.current.toBuilder.setFormat(StorageVersion.StorageFormat.PERSISTENCE_STORE).build
      }

      val migrations = (currentVersion, persistenceStore) match {
        case (Some(version), _) if version < minSupportedStorageVersion =>
          val msg = s"Migration from versions < ${minSupportedStorageVersion.str} are not supported. " +
            s"Your version: ${version.str}"
          throw new MigrationFailedException(msg)
        case (Some(version), None) if version.getFormat == StorageVersion.StorageFormat.PERSISTENCE_STORE =>
          val msg = "Migration from this storage format back to the legacy storage format is not supported."
          throw new MigrationFailedException(msg)
        case (Some(version), _) if version > currentBuildVersion =>
          val msg = s"Migration from ${version.str} is not supported as it is newer" +
            s" than ${StorageVersions.current.str}."
          throw new MigrationFailedException(msg)
        case (Some(version), newStore) if version < currentBuildVersion =>
          val result = await(applyMigrationSteps(version))
          await(storeCurrentVersion())
          result
        case (Some(version), _) if version == currentBuildVersion =>
          logger.info("No migration necessary, already at the current version")
          Nil
        case _ =>
          logger.info("No migration necessary, no version stored")
          await(storeCurrentVersion())
          Nil
      }
      await(closeLegacyStore)
      migrations
    }.recover {
      case ex: MigrationFailedException => throw ex
      case NonFatal(ex) => throw new MigrationFailedException(s"Migration Failed: ${ex.getMessage}", ex)
    }

    val migrations = Await.result(result, Duration.Inf)
    logger.info(s"Migration successfully applied for version ${StorageVersions.current.str}")
    migrations
  }

  // get the version out of persistence store, if that fails, get the version from the legacy store, if we're
  // using a legacy store.
  @SuppressWarnings(Array("all")) // async/await
  private def getCurrentVersion(legacyStore: Option[PersistentStore]): Future[Option[StorageVersion]] =
    async { // linter:ignore UnnecessaryElseBranch
      await {
        persistenceStore.map(_.storageVersion()).orElse {
          legacyStore.map(_.load(StorageVersionName).map(_.map(v => StorageVersion.parseFrom(v.bytes.toArray))))
        }.getOrElse(Future.successful(Some(StorageVersions.current)))
      }
    }

  @SuppressWarnings(Array("all")) // async/await
  private def storeCurrentVersion(): Future[Done] = async { // linter:ignore UnnecessaryElseBranch
    val legacyStore: Option[PersistentStore] = await(legacyStoreFuture)
    val storageVersionFuture: Future[Done] = persistenceStore match {
      case Some(store) => store.setStorageVersion(StorageVersions.current)
      case None =>
        val bytes = StorageVersions.current.toByteArray
        legacyStore.map { store =>
          store.load(StorageVersionName).flatMap {
            case Some(entity) => store.update(entity.withNewContent(bytes))
            case None => store.create(StorageVersionName, bytes)
          }.map(_ => Done)
        }.getOrElse(Future.successful(Done))
    }
    await(storageVersionFuture)
  }

  @SuppressWarnings(Array("all")) // async/await
  private def closeLegacyStore: Future[Done] = async { // linter:ignore UnnecessaryElseBranch
    val legacyStore = await(legacyStoreFuture)
    val future = legacyStore.map {
      case s: PersistentStoreManagement with PrePostDriverCallback =>
        s.postDriverTerminates.flatMap(_ => s.close())
      case s: PersistentStoreManagement =>
        s.close()
      case s: PrePostDriverCallback =>
        s.postDriverTerminates.map(_ => Done)
      case _ =>
        Future.successful(Done)
    }.getOrElse(Future.successful(Done))
    await(future)
  }
}

object Migration {
  val StorageVersionName = "internal:storage:version"
}

object StorageVersions {
  val VersionRegex = """^(\d+)\.(\d+)\.(\d+).*""".r

  def apply(major: Int, minor: Int, patch: Int,
    format: StorageVersion.StorageFormat = StorageVersion.StorageFormat.LEGACY): StorageVersion = {
    StorageVersion
      .newBuilder()
      .setMajor(major)
      .setMinor(minor)
      .setPatch(patch)
      .setFormat(format)
      .build()
  }

  def current: StorageVersion = {
    BuildInfo.version match {
      case VersionRegex(major, minor, patch) =>
        StorageVersions(
          major.toInt,
          minor.toInt,
          patch.toInt,
          StorageVersion.StorageFormat.LEGACY
        )
    }
  }

  implicit class OrderedStorageVersion(val version: StorageVersion) extends AnyVal with Ordered[StorageVersion] {
    override def compare(that: StorageVersion): Int = {
      def by(left: Int, right: Int, fn: => Int): Int = if (left.compareTo(right) != 0) left.compareTo(right) else fn
      by(version.getFormat.getNumber, that.getFormat.getNumber,
        by(version.getMajor, that.getMajor,
          by(version.getMinor, that.getMinor,
            by(version.getPatch, that.getPatch, 0))))
    }

    def str: String = s"Version(${version.getMajor}, ${version.getMinor}, ${version.getPatch}, ${version.getFormat})"

    def nonEmpty: Boolean = !version.equals(empty)
  }

  def empty: StorageVersion = StorageVersions(0, 0, 0, StorageVersion.StorageFormat.LEGACY)
}
