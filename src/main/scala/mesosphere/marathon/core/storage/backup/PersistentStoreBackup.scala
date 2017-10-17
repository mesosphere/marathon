package mesosphere.marathon
package core.storage.backup

import java.net.URI

import akka.Done
import akka.actor.ActorSystem
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.backup.impl.PersistentStoreBackupImpl
import mesosphere.marathon.core.storage.store.PersistenceStore

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Backup & Restore functionality for configured persistent store and backup location.
  */
trait PersistentStoreBackup {

  /**
    * Backup the state of the configured persistent store.
    * @param to the location to write this backup to.
    * @return A future which succeeds, if the backup is written completely.
    */
  def backup(to: URI): Future[Done]

  /**
    * Restore the state from a given backup.
    * @param from the location to read this backup from.
    * @return a future which succeeds, if the backup is restored completely.
    */
  def restore(from: URI): Future[Done]

}

object PersistentStoreBackup extends StrictLogging {
  def apply(store: PersistenceStore[_, _, _])(implicit materializer: Materializer, actorSystem: ActorSystem, ec: ExecutionContext): PersistentStoreBackup = {
    new PersistentStoreBackupImpl(store)
  }
}
