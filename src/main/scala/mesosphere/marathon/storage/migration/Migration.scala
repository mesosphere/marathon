package mesosphere.marathon
package storage.migration

import akka.actor.Scheduler
import java.net.URI

import akka.Done
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.async.ExecutionContexts.global
import mesosphere.marathon.core.storage.backup.PersistentStoreBackup
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.storage.StorageConfig
import mesosphere.marathon.storage.repository._

import scala.async.Async.{ async, await }
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal
import scala.util.matching.Regex
import mesosphere.marathon.raml.RuntimeConfiguration

/**
  * @param persistenceStore Optional "new" PersistenceStore for new migrations, the repositories
  *                         are assumed to be in the new format.
  */
@SuppressWarnings(Array("UnusedMethodParameter")) // materializer will definitely be used in the future.
class Migration(
    private[migration] val availableFeatures: Set[String],
    private[migration] val defaultNetworkName: Option[String],
    private[migration] val mesosBridgeName: String,
    private[migration] val persistenceStore: PersistenceStore[_, _, _],
    private[migration] val appRepository: AppRepository,
    private[migration] val podRepository: PodRepository,
    private[migration] val groupRepository: GroupRepository,
    private[migration] val deploymentRepository: DeploymentRepository,
    private[migration] val instanceRepo: InstanceRepository,
    private[migration] val taskFailureRepo: TaskFailureRepository,
    private[migration] val frameworkIdRepo: FrameworkIdRepository,
    private[migration] val serviceDefinitionRepo: ServiceDefinitionRepository,
    private[migration] val runtimeConfigurationRepository: RuntimeConfigurationRepository,
    private[migration] val backup: PersistentStoreBackup,
    private[migration] val config: StorageConfig
)(implicit mat: Materializer, scheduler: Scheduler) extends StrictLogging {

  import StorageVersions._
  import Migration.{ MigrationAction, statusLoggingInterval }

  private[migration] val minSupportedStorageVersion = StorageVersions(1, 4, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE)

  /**
    * All the migrations, that have to be applied.
    * They get applied after the master has been elected.
    */
  def migrations: List[MigrationAction] =
    List(
      StorageVersions(1, 4, 2, StorageVersion.StorageFormat.PERSISTENCE_STORE) -> { () =>
        new MigrationTo142(appRepository).migrate()
      },
      StorageVersions(1, 4, 6, StorageVersion.StorageFormat.PERSISTENCE_STORE) -> { () =>
        new MigrationTo146(appRepository, podRepository).migrate()
      },
      StorageVersions(1, 5, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE) -> (() =>
        MigrationTo15(this).migrate()
      )
    )

  protected def notifyMigrationInProgress(from: StorageVersion, migrateVersion: StorageVersion) = {
    logger.info(
      s"Migration for storage: ${from.str} to current: ${current.str}: " +
        s"application of the change for version ${migrateVersion.str} is still in progress"
    )
  }

  def applyMigrationSteps(from: StorageVersion): Future[Seq[StorageVersion]] = {
    migrations.filter(_._1 > from).sortBy(_._1).foldLeft(Future.successful(Seq.empty[StorageVersion])) {
      case (resultsFuture, (migrateVersion, change)) => resultsFuture.flatMap { res =>
        logger.info(
          s"Migration for storage: ${from.str} to current: ${current.str}: " +
            s"apply change for version: ${migrateVersion.str} "
        )

        val migrationInProgressNotification = scheduler.schedule(statusLoggingInterval, statusLoggingInterval) {
          notifyMigrationInProgress(from, migrateVersion)
        }

        change.apply().recover {
          case NonFatal(e) =>
            throw new MigrationFailedException(s"while migrating storage to $migrateVersion", e)
        }.map { _ =>
          res :+ migrateVersion
        }.andThen {
          case _ =>
            migrationInProgressNotification.cancel()
        }
      }
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  def migrateAsync(): Future[Seq[StorageVersion]] = async {
    // Before reading to and writing from the storage, let's ensure that
    // no stale values are read from the persistence store.
    // Although in case of ZK it is done at the time of creation of CuratorZK,
    // it is better to be safe than sorry.
    logger.info(s"$persistenceStore")
    await(persistenceStore.sync())

    val config = await(runtimeConfigurationRepository.get()).getOrElse(RuntimeConfiguration())
    // before backup/restore called, reset the runtime configuration
    await(runtimeConfigurationRepository.store(RuntimeConfiguration(None, None)))
    // step 1: backup current zk state
    await(config.backup.map(uri => backup.backup(new URI(uri))).getOrElse(Future.successful(Done)))
    // step 2: restore state from given backup
    await(config.restore.map(uri => backup.restore(new URI(uri))).getOrElse(Future.successful(Done)))
    // last step run the migration, to ensure we can operate on the zk state
    val result = await(migrateStorage(backupCreated = config.backup.isDefined || config.restore.isDefined))
    logger.info(s"Migration successfully applied for version ${StorageVersions.current.str}")
    result
  }

  def migrate(): Seq[StorageVersion] =
    Await.result(migrateAsync(), Duration.Inf)

  @SuppressWarnings(Array("all")) // async/await
  def migrateStorage(backupCreated: Boolean = false): Future[Seq[StorageVersion]] = {
    async {
      val currentVersion = await(getCurrentVersion)
      val currentBuildVersion = StorageVersions.current

      val migrations = currentVersion match {
        case Some(version) if version < minSupportedStorageVersion =>
          val msg = s"Migration from versions < ${minSupportedStorageVersion.str} are not supported. " +
            s"Your version: ${version.str}"
          throw new MigrationFailedException(msg)
        case Some(version) if version > currentBuildVersion =>
          val msg = s"Migration from ${version.str} is not supported as it is newer" +
            s" than ${StorageVersions.current.str}."
          throw new MigrationFailedException(msg)
        case Some(version) if version < currentBuildVersion =>
          if (!backupCreated && config.backupLocation.isDefined) {
            logger.info("Backup current state")
            await(backup.backup(config.backupLocation.get))
            logger.info("Backup finished. Apply migration.")
          }
          val result = await(applyMigrationSteps(version))
          await(storeCurrentVersion())
          result
        case Some(version) if version == currentBuildVersion =>
          logger.info("No migration necessary, already at the current version")
          Nil
        case _ =>
          logger.info("No migration necessary, no version stored")
          await(storeCurrentVersion())
          Nil
      }
      migrations
    }.recover {
      case ex: MigrationFailedException => throw ex
      case NonFatal(ex) =>
        throw new MigrationFailedException(s"Migration Failed: ${ex.getMessage}", ex)
    }
  }

  private def getCurrentVersion: Future[Option[StorageVersion]] =
    persistenceStore.storageVersion()

  private def storeCurrentVersion(): Future[Done] =
    persistenceStore.setStorageVersion(StorageVersions.current)
}

object Migration {
  val StorageVersionName = "internal:storage:version"
  val statusLoggingInterval = 10.seconds

  type MigrationAction = (StorageVersion, () => Future[Any])

}

object StorageVersions {
  val VersionRegex: Regex = """^(\d+)\.(\d+)\.(\d+).*""".r

  def apply(major: Int, minor: Int, patch: Int,
    format: StorageVersion.StorageFormat = StorageVersion.StorageFormat.PERSISTENCE_STORE): StorageVersion = {
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
          StorageVersion.StorageFormat.PERSISTENCE_STORE
        )
      case BuildInfo.DefaultBuildVersion =>
        StorageVersions(
          BuildInfo.DefaultMajor,
          BuildInfo.DefaultMinor,
          BuildInfo.DefaultPatch,
          StorageVersion.StorageFormat.PERSISTENCE_STORE
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
