package mesosphere.marathon
package storage

import mesosphere.marathon.core.storage.backup.BackupConf
import org.rogach.scallop.ScallopOption

trait StorageConf extends ZookeeperConf with BackupConf {
  lazy val internalStoreBackend = opt[String](
    "internal_store_backend",
    descr = s"The backend storage system to use. One of ${InMem.StoreName}, ${CuratorZk.StoreName}",
    hidden = true,
    validate = Set(InMem.StoreName, CuratorZk.StoreName).contains,
    default = Some(CuratorZk.StoreName)
  )

  lazy val storeCache = toggle(
    "store_cache",
    default = Some(true),
    noshort = true,
    descrYes = "(Default) Enable an in-memory cache for the storage layer.",
    descrNo = "Disable the in-memory cache for the storage layer. ",
    prefix = "disable_"
  )

  lazy val maxVersions = opt[Int](
    "zk_max_versions", // while called Zk, applies to every store but the name is kept
    descr = "Limit the number of versions, stored for one entity.",
    default = Some(50)
  )

  lazy val zkCleaningInterval = opt[Int](
    "zk_cleaning_interval",
    descr = "ZK cleaning interval in seconds",
    default = Some(30)
  )

  lazy val gcActorScanBatchSize = opt[Int](
    "gc_actor_scan_batch_size",
    descr = "Size of GC actor scan batches.",
    default = Some(32)
  )

  lazy val zkMaxConcurrency = opt[Int](
    "zk_max_concurrency",
    default = Some(32),
    hidden = true,
    descr = "Max outstanding requests to Zookeeper persistence"
  )

  lazy val versionCacheEnabled = toggle(
    "internal_version_caching",
    default = Some(true),
    noshort = true,
    hidden = true,
    descrYes = "(Default) Enable an additional layer of caching for object versions when store_cache is enabled.",
    prefix = "disable_"
  )

  lazy val storageExecutionContextSize = opt[Int](
    "storage_execution_context_size",
    default = Some(Runtime.getRuntime().availableProcessors()),
    hidden = true,
    descr = "INTERNAL TUNING PARAMETER: Storage module's execution context thread pool size"
  )

  def defaultNetworkName: ScallopOption[String]

  def availableFeatures: Set[String]
}
