package mesosphere.marathon
package core.storage.store.impl.cache

import java.util.UUID

import com.mesosphere.utils.zookeeper.ZookeeperServerTest
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.base.JvmExitsCrashStrategy
import mesosphere.marathon.core.storage.store.PersistenceStoreTest
import mesosphere.marathon.core.storage.store.impl.InMemoryTestClass1Serialization
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.{RichCuratorFramework, ZkPersistenceStore, ZkTestClass1Serialization}
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.storage.store.InMemoryStoreSerialization

class LoadTimeCachingPersistenceStoreTest
    extends AkkaUnitTest
    with PersistenceStoreTest
    with ZookeeperServerTest
    with ZkTestClass1Serialization
    with InMemoryStoreSerialization
    with InMemoryTestClass1Serialization {

  private val metrics = DummyMetrics

  def zkStore: ZkPersistenceStore = {
    val root = UUID.randomUUID().toString
    val rootZkClient = RichCuratorFramework(zkClient(namespace = Some(root)), JvmExitsCrashStrategy)
    new ZkPersistenceStore(metrics, rootZkClient)
  }

  private def cachedInMemory = {
    val store = new LoadTimeCachingPersistenceStore(new InMemoryPersistenceStore(metrics))
    store.markOpen()
    store.preDriverStarts.futureValue
    store
  }

  private def cachedZk = {
    val store = new LoadTimeCachingPersistenceStore(zkStore)
    store.markOpen()
    store.preDriverStarts.futureValue
    store
  }

  behave like basicPersistenceStore("LoadTime(InMemory)", cachedInMemory)
  behave like basicPersistenceStore("LoadTime(Zk)", cachedZk)
  // TODO: Mock out the backing store
}
