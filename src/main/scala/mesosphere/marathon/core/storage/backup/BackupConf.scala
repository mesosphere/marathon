package mesosphere.marathon
package core.storage.backup

import java.net.URI

import mesosphere.marathon.stream.UriIO
import org.rogach.scallop.ScallopConf

import scala.util.Try

/**
  * Defines configuration parameter for the backup module.
  * Please see [[mesosphere.marathon.storage.StorageModule]] which exposes backup functionality.
  */
trait BackupConf extends ScallopConf {

  lazy val backupLocation = opt[String](
    "backup_location",
    descr = "The location uri of the backup to create.",
    validate = loc => Try(new URI(loc)).map(UriIO.isValid).getOrElse(false),
    noshort = true
  ).map(new URI(_))
}
