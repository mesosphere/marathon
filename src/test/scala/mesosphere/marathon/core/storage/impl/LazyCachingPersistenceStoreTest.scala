package mesosphere.marathon.core.storage.impl

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.PersistenceStoreTest
import mesosphere.marathon.core.storage.impl.zk.{ TestClass1Implicits, ZkPersistenceStore }
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics

class LazyCachingPersistenceStoreTest extends AkkaUnitTest
    with PersistenceStoreTest with TestClass1Implicits with ZookeeperServerTest
    with InMemoryStoreSerialization with InMemoryTestClass1Serialization {

  implicit val scheduler = system.scheduler
  implicit val metrics = new Metrics(new MetricRegistry)

  private def cachedInMemory = new LazyCachingPersistenceStore(new InMemoryPersistenceStore())

  def zkStore: ZkPersistenceStore = {
    val client = zkClient()
    val root = UUID.randomUUID().toString
    client.create(s"/$root").futureValue
    new ZkPersistenceStore(client.usingNamespace(root))
  }

  private def cachedZk = new LazyCachingPersistenceStore(zkStore)

  "LazyCachingPersistenceStore" when {
    "backed by InMemoryPersistenceStore" should {
      behave like basePersistenceStore(cachedInMemory)
    }
    "backed by ZkPersistenceStore" should {
      behave like basePersistenceStore(cachedZk)
    }
    // TODO: Mock out the backing store.
  }
}
