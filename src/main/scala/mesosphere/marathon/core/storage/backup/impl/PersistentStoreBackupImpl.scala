package mesosphere.marathon
package core.storage.backup.impl

import java.net.URI

import akka.Done
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Keep }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.backup.{ BackupItem, PersistentStoreBackup }
import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.stream.UriIO

import scala.concurrent.{ ExecutionContext, Future }

class PersistentStoreBackupImpl(store: PersistenceStore[_, _, _])(implicit materializer: Materializer, actorSystem: ActorSystem, ec: ExecutionContext)
    extends PersistentStoreBackup with StrictLogging {

  override def backup(to: URI): Future[Done] = {
    logger.info(s"Create backup at $to")
    store.backup()
      .via(logFlow("Backup"))
      .via(TarBackupFlow.tar)
      .toMat(UriIO.writer(to))(Keep.right)
      .mapMaterializedValue(_.map(_ => Done))
      .run()
  }

  override def restore(from: URI): Future[Done] = {
    logger.info(s"Restore backup from $from")
    UriIO.reader(from)
      .via(TarBackupFlow.untar)
      .via(logFlow("Restore"))
      .toMat(store.restore())(Keep.both)
      .mapMaterializedValue{ case (f1, f2) => f1.zip(f2).map(_ => Done) }
      .run()
  }

  private[this] def logFlow(message: String) = Flow.fromFunction[BackupItem, BackupItem] { item =>
    logger.info(s"$message item category:${item.category} key:${item.key} version:${item.version}")
    item
  }
}

