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

  lazy val maxVersions = opt[Int](
    "zk_max_versions", // while called Zk, applies to every store but the name is kept
    descr = "Limit the number of versions, stored for one entity.",
    default = internalStoreBackend() match {
      case TwitterZk.StoreName => Some(25)
      case _ => Some(50)
    }
  )
}
