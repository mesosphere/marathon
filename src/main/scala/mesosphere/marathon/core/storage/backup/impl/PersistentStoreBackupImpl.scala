package mesosphere.marathon
package core.storage.backup.impl

import java.nio.file.Paths

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.storage.backup.PersistentStoreBackup
import mesosphere.marathon.core.storage.store.PersistenceStore

import scala.concurrent.{ ExecutionContext, Future }

class PersistentStoreBackupImpl(store: PersistenceStore[_, _, _], location: String)(implicit materializer: Materializer, ec: ExecutionContext)
    extends PersistentStoreBackup with StrictLogging {

  override def backup(): Future[Done] = {
    logger.info(s"Create backup at $location")
    store.backup()
      .via(TarBackupFlow.tar)
      .runWith(FileIO.toPath(Paths.get(location))).map(_ => Done)
  }

  override def restore(): Future[Done] = {
    logger.info(s"Restore backup from $location")
    FileIO
      .fromPath(Paths.get(location))
      .via(TarBackupFlow.untar)
      .runWith(store.restore())
  }
}

