package mesosphere.marathon.core.storage

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
}
