package mesosphere.marathon
package core.storage.backup.impl

import java.net.URI

import akka.Done
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.backup.{ BackupItem, PersistentStoreBackup }
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.stream.UriIO

import scala.concurrent.{ ExecutionContext, Future }

class PersistentStoreBackupImpl(store: PersistenceStore[_, _, _], location: URI)(implicit materializer: Materializer, actorSystem: ActorSystem, ec: ExecutionContext)
    extends PersistentStoreBackup with StrictLogging {

  override def backup(): Future[Done] = {
    logger.info(s"Create backup at $location")
    store.backup()
      .via(logFlow("Backup"))
      .via(TarBackupFlow.tar)
      .runWith(UriIO.writer(location))
  }

  override def restore(): Future[Done] = {
    logger.info(s"Restore backup from $location")
    UriIO.reader(location)
      .via(TarBackupFlow.untar)
      .via(logFlow("Restore"))
      .runWith(store.restore())
  }

  private[this] def logFlow(message: String) = Flow.fromFunction[BackupItem, BackupItem] { item =>
    logger.info(s"$message item category:${item.category} key:${item.key} version:${item.version}")
    item
  }
}

