package mesosphere.marathon.core.storage.store.impl.cache

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.store.PersistenceStoreTest
import mesosphere.marathon.core.storage.store.impl.InMemoryTestClass1Serialization
import mesosphere.marathon.core.storage.store.impl.memory.{ InMemoryPersistenceStore, InMemoryStoreSerialization }
import mesosphere.marathon.core.storage.store.impl.zk.{ ZkPersistenceStore, ZkTestClass1Serialization }
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics

import scala.concurrent.duration.Duration

class LazyCachingPersistenceStoreTest extends AkkaUnitTest
    with PersistenceStoreTest with ZkTestClass1Serialization with ZookeeperServerTest
    with InMemoryStoreSerialization with InMemoryTestClass1Serialization {

  private def cachedInMemory = {
    implicit val metrics = new Metrics(new MetricRegistry)
    new LazyCachingPersistenceStore(new InMemoryPersistenceStore())
  }

  lazy val rootZkClient = zkClient()

  def zkStore: ZkPersistenceStore = {
    implicit val metrics = new Metrics(new MetricRegistry)

    val root = UUID.randomUUID().toString
    rootZkClient.create(s"/$root").futureValue
    new ZkPersistenceStore(rootZkClient.usingNamespace(root), Duration.Inf, 8)
  }

  private def cachedZk = new LazyCachingPersistenceStore(zkStore)

  behave like basicPersistenceStore("LazyCache(InMemory)", cachedInMemory)
  behave like basicPersistenceStore("LazyCache(Zk)", cachedZk)
  // TODO: Mock out the backing store.
}
