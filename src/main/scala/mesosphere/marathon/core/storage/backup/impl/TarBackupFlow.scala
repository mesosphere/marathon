package mesosphere.marathon
package core.storage.backup.impl

import java.time.OffsetDateTime

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import mesosphere.marathon.core.storage.backup.BackupItem
import mesosphere.marathon.stream.TarFlow
import mesosphere.marathon.stream.TarFlow.TarEntry

object TarBackupFlow {

  lazy val tar: Flow[BackupItem, ByteString, NotUsed] = {
    def backupItemToTarEntry(item: BackupItem): TarEntry = {
      val path = item.version match {
        case Some(dt) => s"${item.category}/versions/${item.key}/$dt"
        case None => s"${item.category}/items/${item.key}"
      }
      TarEntry(path, item.data)
    }
    Flow[BackupItem].map(backupItemToTarEntry).via(TarFlow.writer)
  }

  lazy val untar: Flow[ByteString, BackupItem, NotUsed] = {
    val CategoryItemRegexp = "^([^/]+)/items/(.+)$".r
    val CategoryItemWithVersionRegexp = "^([^/]+)/versions/([^/]+)/(.+)$".r

    def tarEntryToBackupItem(entry: TarEntry): BackupItem = {
      entry.header.getName match {
        case CategoryItemWithVersionRegexp(category, key, version) => BackupItem(category, key, Some(OffsetDateTime.parse(version)), entry.data)
        case CategoryItemRegexp(category, key) => BackupItem(category, key, None, entry.data)
        case key => throw new IllegalArgumentException(s"Can not read: $key")
      }
    }
    Flow[ByteString].via(TarFlow.reader).map(tarEntryToBackupItem)
  }
}

