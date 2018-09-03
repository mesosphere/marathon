package mesosphere.marathon
package storage.migration

import mesosphere.marathon.core.storage.store.PersistenceStore
import mesosphere.marathon.core.storage.store.impl.cache.{LazyCachingPersistenceStore, LazyVersionCachingPersistentStore, LoadTimeCachingPersistenceStore}
import mesosphere.marathon.core.storage.store.impl.zk.ZkPersistenceStore

/**
  * Provides a help to get access to the underlying ZooKeeper store of the provided persistence store.
  * See [[MigrationTo160]] for usage.
  */
trait MaybeStore {

  private def findZkStore(ps: PersistenceStore[_, _, _]): Option[ZkPersistenceStore] = {
    ps match {
      case zk: ZkPersistenceStore =>
        Some(zk)
      case lcps: LazyCachingPersistenceStore[_, _, _] =>
        findZkStore(lcps.store)
      case lvcps: LazyVersionCachingPersistentStore[_, _, _] =>
        findZkStore(lvcps.store)
      case ltcps: LoadTimeCachingPersistenceStore[_, _, _] =>
        findZkStore(ltcps.store)
      case other =>
        None
    }
  }

  /**
    * We're trying to find if we have a ZooKeeper store because it provides objects as byte arrays and this
    * makes serialization into json easier.
    */
  def maybeStore(persistenceStore: PersistenceStore[_, _, _]): Option[ZkPersistenceStore] = findZkStore(persistenceStore)

}
