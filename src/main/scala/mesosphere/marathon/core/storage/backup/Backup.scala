package mesosphere.marathon
package core.storage.backup

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import ch.qos.logback.classic.{Level, Logger}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.base.{JvmExitsCrashStrategy, LifecycleState}
import mesosphere.marathon.core.storage.store.impl.zk.RichCuratorFramework
import mesosphere.marathon.metrics.MetricsConf
import mesosphere.marathon.storage.{StorageConf, StorageConfig, StorageModule}
import org.rogach.scallop.ScallopConf
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

/**
  * Base class for backup and restore command line utility.
  */
abstract class BackupRestoreAction extends StrictLogging {

  class BackupConfig(args: Seq[String])
    extends ScallopConf(args) with StorageConf with NetworkConf with MetricsConf with FeaturesConf {
    override def availableFeatures: Set[String] = Set.empty
    verify()
    require(backupLocation.isDefined, "--backup_location needs to be defined!")
  }

  /**
    * Can either run a backup or restore operation.
    */
  def action(conf: BackupConfig, fn: PersistentStoreBackup => Future[Done]): Unit = {
    implicit val system = ActorSystem("Backup")
    implicit val materializer = ActorMaterializer()
    implicit val scheduler = system.scheduler
    import scala.concurrent.ExecutionContext.Implicits.global

    val metricsModule = MetricsModule(conf)
    metricsModule.start(system)

    try {
      val curatorFramework: RichCuratorFramework = StorageConfig.curatorFramework(conf, JvmExitsCrashStrategy, LifecycleState.WatchingJVM)
      val storageModule = StorageModule(metricsModule.metrics, conf, curatorFramework)
      storageModule.persistenceStore.markOpen()
      val backup = storageModule.persistentStoreBackup
      Await.result(fn(backup), Duration.Inf)
      storageModule.persistenceStore.markClosed()
      logger.info("Action complete.")
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Error: ${ex.getMessage}", ex)
        sys.exit(1) // signal a problem to the caller
    } finally {
      Await.result(Http().shutdownAllConnectionPools(), Duration.Inf)
      // akka http has an issue tearing down the connection pool: https://github.com/akka/akka-http/issues/907
      // We will hide the fail message from the user until this is fixed
      LoggerFactory.getLogger("akka.actor.ActorSystemImpl").asInstanceOf[Logger].setLevel(Level.OFF)
      materializer.shutdown()
      Await.ready(system.terminate(), Duration.Inf)
      sys.exit(0)
    }
  }
}

/**
  * Command line utility to backup the current Marathon state to an external storage location.
  *
  * Please note: if you start Marathon with a backup location, it will automatically create a backup,
  * for every new Marathon version, before it runs a migration.
  * This is the preferred way to handle upgrades.
  *
  * Snapshot backups can be created at all time.
  *
  * There are several command line parameters to define the exact behaviour and location.
  * Please use --help to see all command line parameters
  */
object Backup extends BackupRestoreAction {
  def main(args: Array[String]): Unit = {
    val config = new BackupConfig(args.toVector)
    action(config, _.backup(config.backupLocation()))
  }
}

/**
  * Command line utility to restore a Marathon state from an external storage location.
  *
  * Please note: restoring a backup will overwrite all existing data in the store.
  * All changes that were applied between the creation of this snapshot to the current state will be lost!
  *
  * There are several command line parameters to define the exact behaviour and location.
  * Please use --help to see all command line parameters
  */
object Restore extends BackupRestoreAction {
  def main(args: Array[String]): Unit = {
    val config = new BackupConfig(args.toVector)
    action(config, _.restore(config.backupLocation()))
  }
}
