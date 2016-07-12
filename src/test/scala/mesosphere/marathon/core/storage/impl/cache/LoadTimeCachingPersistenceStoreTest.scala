package mesosphere.marathon.core.storage.impl.cache

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.PersistenceStoreTest
import mesosphere.marathon.core.storage.impl.zk.{ TestClass1Implicits, ZkPersistenceStore }
import mesosphere.marathon.core.storage.impl.{ InMemoryPersistenceStore, InMemoryStoreSerialization, InMemoryTestClass1Serialization }
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics

class LoadTimeCachingPersistenceStoreTest extends AkkaUnitTest
    with PersistenceStoreTest with ZookeeperServerTest with TestClass1Implicits
    with InMemoryStoreSerialization with InMemoryTestClass1Serialization {

  implicit val metrics = new Metrics(new MetricRegistry)
  implicit val scheduler = system.scheduler

  def zkStore: ZkPersistenceStore = {
    val client = zkClient()
    val root = UUID.randomUUID().toString
    client.create(s"/$root").futureValue
    new ZkPersistenceStore(client.usingNamespace(root))
  }

  private def cachedInMemory = {
    val store = new LoadTimeCachingPersistenceStore(new InMemoryPersistenceStore())
    store.preDriverStarts.futureValue
    store
  }

  private def cachedZk = {
    val store = new LoadTimeCachingPersistenceStore(zkStore)
    store.preDriverStarts.futureValue
    store
  }

  "LoadTimeCachingPersistenceStore" when {
    "backed by InMemoryPersistenceStore" should {
      behave like emptyPersistenceStore(cachedInMemory)
    }
    "backed by ZkPersistenceStore" should {
      behave like emptyPersistenceStore(cachedZk)
    }
    // TODO: Mock out the backing store
  }
}
