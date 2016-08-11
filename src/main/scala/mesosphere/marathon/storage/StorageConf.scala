package mesosphere.marathon.storage

import mesosphere.marathon.ZookeeperConf

trait StorageConf extends ZookeeperConf {
  lazy val internalStoreBackend = opt[String](
    "internal_store_backend",
    descr = s"The backend storage system to use. One of ${TwitterZk.StoreName}, ${MesosZk.StoreName}, ${InMem.StoreName}, ${CuratorZk.StoreName}", // scalastyle:off
    hidden = true,
    validate = Set(TwitterZk.StoreName, MesosZk.StoreName, InMem.StoreName, CuratorZk.StoreName).contains,
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

  lazy val zkMaxConcurrency = opt[Int](
    "zk_max_concurrency",
    default = Some(32), // scalastyle:off magic.number
    hidden = true,
    descr = "Max outstanding requests to Zookeeper persistence"
  )
}
