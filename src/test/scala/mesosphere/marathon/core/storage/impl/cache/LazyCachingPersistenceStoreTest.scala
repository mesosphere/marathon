package mesosphere.marathon.core.storage.impl.cache

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.PersistenceStoreTest
import mesosphere.marathon.core.storage.impl.InMemoryTestClass1Serialization
import mesosphere.marathon.core.storage.impl.memory.{ InMemoryPersistenceStore, InMemoryStoreSerialization }
import mesosphere.marathon.core.storage.impl.zk.{ ZkPersistenceStore, ZkTestClass1Serialization }
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics

import scala.concurrent.duration.Duration

class LazyCachingPersistenceStoreTest extends AkkaUnitTest
    with PersistenceStoreTest with ZkTestClass1Serialization with ZookeeperServerTest
    with InMemoryStoreSerialization with InMemoryTestClass1Serialization {
  implicit val metrics = new Metrics(new MetricRegistry)

  private def cachedInMemory = new LazyCachingPersistenceStore(new InMemoryPersistenceStore())

  def zkStore: ZkPersistenceStore = {
    val client = zkClient()
    val root = UUID.randomUUID().toString
    client.create(s"/$root").futureValue
    new ZkPersistenceStore(client.usingNamespace(root), Duration.Inf, 8)
  }

  private def cachedZk = new LazyCachingPersistenceStore(zkStore)

  behave like basicPersistenceStore("LazyCache(InMemory)", cachedInMemory)
  behave like basicPersistenceStore("LazyCache(Zk)", cachedZk)
  // TODO: Mock out the backing store.
}
