package mesosphere.marathon
package core.storage.backup

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.StrictLogging
import kamon.Kamon
import mesosphere.marathon.core.base.LifecycleState
import mesosphere.marathon.storage.{ StorageConf, StorageModule }
import org.rogach.scallop.ScallopConf

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.util.control.NonFatal

/**
  * Base class for backup and restore command line utility.
  */
abstract class BackupRestoreAction extends StrictLogging {

  class BackupConfig(args: Seq[String]) extends ScallopConf(args) with StorageConf with BackupConf {
    override def availableFeatures: Set[String] = Set.empty
    override def defaultNetworkName = opt[String]()
    verify()
    require(backupLocation.isDefined, "--backup_location needs to be defined!")
  }

  /**
    * Can either run a backup or restore operation.
    */
  def action(conf: BackupConfig, fn: PersistentStoreBackup => Future[Done]): Unit = {
    Kamon.start()
    implicit val system = ActorSystem("Backup")
    implicit val materializer = ActorMaterializer()
    implicit val scheduler = system.scheduler
    import scala.concurrent.ExecutionContext.Implicits.global
    try {
      val storageModule = StorageModule(conf, LifecycleState.WatchingJVM)
      val backup = storageModule.persistentStoreBackup
      Await.result(fn(backup), Duration.Inf)
      logger.info("Action complete.")
    } catch {
      case NonFatal(ex) => logger.error(s"Error: ${ex.getMessage}", ex)
    } finally {
      materializer.shutdown()
      system.terminate()
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
    action(new BackupConfig(args.toVector), _.backup())
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
    action(new BackupConfig(args.toVector), _.restore())
  }
}
