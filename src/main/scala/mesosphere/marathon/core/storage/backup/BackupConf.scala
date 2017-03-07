package mesosphere.marathon
package core.storage.backup

import org.rogach.scallop.ScallopConf

/**
  * Defines configuration parameter for the backup module.
  * Please see [[mesosphere.marathon.storage.StorageModule]] which exposes backup functionality.
  */
trait BackupConf extends ScallopConf {

  lazy val backupLocation = opt[String](
    "backup_location",
    descr = "The location of the backup to create.",
    noshort = true
  )
}
