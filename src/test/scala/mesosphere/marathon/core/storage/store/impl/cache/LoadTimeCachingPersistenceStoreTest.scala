package mesosphere.marathon
package core.storage.store.impl.cache

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.store.PersistenceStoreTest
import mesosphere.marathon.core.storage.store.impl.InMemoryTestClass1Serialization
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.{ ZkPersistenceStore, ZkTestClass1Serialization }
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.store.InMemoryStoreSerialization

import scala.concurrent.duration.Duration

class LoadTimeCachingPersistenceStoreTest extends AkkaUnitTest
    with PersistenceStoreTest with ZookeeperServerTest with ZkTestClass1Serialization
    with InMemoryStoreSerialization with InMemoryTestClass1Serialization {

  def zkStore: ZkPersistenceStore = {
    implicit val metrics = new Metrics(new MetricRegistry)

    val root = UUID.randomUUID().toString
    val rootZkClient = zkClient(namespace = Some(root))
    new ZkPersistenceStore(rootZkClient, Duration.Inf)
  }

  private def cachedInMemory = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = new LoadTimeCachingPersistenceStore(new InMemoryPersistenceStore())
    store.preDriverStarts.futureValue
    store
  }

  private def cachedZk = {
    val store = new LoadTimeCachingPersistenceStore(zkStore)
    store.preDriverStarts.futureValue
    store
  }

  behave like basicPersistenceStore("LoadTime(InMemory)", cachedInMemory)
  behave like basicPersistenceStore("LoadTime(Zk)", cachedZk)
  // TODO: Mock out the backing store
}
