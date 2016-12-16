package mesosphere.marathon
package storage.migration

import akka.Done
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.repository._

import scala.async.Async.{ async, await }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal
import scala.util.matching.Regex

/**
  * @param persistenceStore Optional "new" PersistenceStore for new migrations, the repositories
  *                         are assumed to be in the new format.
  */
@SuppressWarnings(Array("UnusedMethodParameter")) // materializer will definitely be used in the future.
class Migration(
    private[migration] val availableFeatures: Set[String],
    private[migration] val persistenceStore: PersistenceStore[_, _, _],
    private[migration] val appRepository: AppRepository,
    private[migration] val groupRepository: GroupRepository,
    private[migration] val deploymentRepository: DeploymentRepository,
    private[migration] val taskRepo: TaskRepository,
    private[migration] val instanceRepo: InstanceRepository,
    private[migration] val taskFailureRepo: TaskFailureRepository,
    private[migration] val frameworkIdRepo: FrameworkIdRepository,
    private[migration] val eventSubscribersRepo: EventSubscribersRepository)(implicit
  mat: Materializer,
    metrics: Metrics) extends StrictLogging {

  import StorageVersions._

  type MigrationAction = (StorageVersion, () => Future[Any])

  private[migration] val minSupportedStorageVersion = StorageVersions(1, 4, 0, StorageVersion.StorageFormat.PERSISTENCE_STORE)

  /**
    * All the migrations, that have to be applied.
    * They get applied after the master has been elected.
    */
  def migrations: List[MigrationAction] = List.empty

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
    val result = async {
      val currentVersion = await(getCurrentVersion())

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
      case NonFatal(ex) => throw new MigrationFailedException(s"Migration Failed: ${ex.getMessage}", ex)
    }

    val migrations = Await.result(result, Duration.Inf)
    logger.info(s"Migration successfully applied for version ${StorageVersions.current.str}")
    migrations
  }

  private def getCurrentVersion(): Future[Option[StorageVersion]] =
    persistenceStore.storageVersion()

  private def storeCurrentVersion(): Future[Done] =
    persistenceStore.setStorageVersion(StorageVersions.current)
}

object Migration {
  val StorageVersionName = "internal:storage:version"
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
