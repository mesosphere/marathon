package mesosphere.marathon
package core.storage.backup

import java.time.OffsetDateTime

import akka.util.ByteString

/**
  * All items in any persistent storage has to be transformed into a ByteArray with a given key.
  *
  * @param category the category of this backup item.
  * @param key the identifier of the item to backup.
  * @param version the optional version of this backup item.
  * @param data the data as bytes to backup.
  */
case class BackupItem(category: String, key: String, version: Option[OffsetDateTime], data: ByteString)

