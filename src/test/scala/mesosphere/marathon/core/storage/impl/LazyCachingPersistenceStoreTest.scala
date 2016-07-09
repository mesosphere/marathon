/*package mesosphere.marathon.core.storage.impl

import java.util.UUID

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.PersistenceStoreTest
import mesosphere.marathon.core.storage.impl.zk.{ TestClass1Implicits, ZkPersistenceStore }
import mesosphere.marathon.integration.setup.ZookeeperServerTest

class LazyCachingPersistenceStoreTest extends AkkaUnitTest
    with PersistenceStoreTest with TestClass1Implicits with ZookeeperServerTest with InMemoryStoreSerialization {
  val rootId = ""
  val createId = s"${UUID.randomUUID().toString}"

  implicit val scheduler = system.scheduler

  val cachedInMemory = new LazyCachingPersistenceStore(new InMemoryPersistenceStore())

  def zkStore: ZkPersistenceStore = {
    val client = zkClient()
    val root = createId
    client.create(s"/$root").futureValue
    new ZkPersistenceStore(client.usingNamespace(root))
  }

  lazy val cachedZk = new LazyCachingPersistenceStore(zkStore)

  "LazyCachingPersistenceStore" when {
    "backed by InMemoryPersistenceStore" should {
      behave like singleTypeStore(cachedInMemory)
    }
    "backed by ZkPersistenceStore" should {
      behave like singleTypeStore(cachedZk)
    }
    // TODO: Mock out the backing store.
  }
}
*/