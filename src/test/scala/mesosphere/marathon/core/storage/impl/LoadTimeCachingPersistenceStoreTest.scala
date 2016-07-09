/*package mesosphere.marathon.core.storage.impl

import java.util.UUID

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.PersistenceStoreTest
import mesosphere.marathon.core.storage.impl.zk.{ TestClass1Implicits, ZkId, ZkPersistenceStore }
import mesosphere.marathon.integration.setup.ZookeeperServerTest

class LoadTimeCachingPersistenceStoreTest extends AkkaUnitTest
    with PersistenceStoreTest with ZookeeperServerTest with TestClass1Implicits
    with InMemoryStoreSerialization {
  val rootId = ""
  def createId: String = s"${UUID.randomUUID().toString}"

  implicit val scheduler = system.scheduler

  def zkStore: ZkPersistenceStore = {
    val client = zkClient()
    val root = createId
    client.create(s"/$root").futureValue
    new ZkPersistenceStore(client.usingNamespace(root))
  }

  val cachedInMemory = {
    val store = new LoadTimeCachingPersistenceStore(
      new InMemoryPersistenceStore(),
      (ramId: RamId) => ramId.id,
      (id: String) => RamId(id))
    store.preDriverStarts
    store
  }

  lazy val cachedZk = {
    val store = new LoadTimeCachingPersistenceStore(
      zkStore,
      (zkId: ZkId) => zkId.id,
      (id: String) => ZkId(id))
    store.preDriverStarts
    store
  }

  "LoadTimeCachingPersistenceStore" when {
    "backed by InMemoryPersistenceStore" should {
      behave like singleTypeStore(cachedInMemory)
    }
    "backed by ZkPersistenceStore" should {
      behave like singleTypeStore(cachedZk)
    }
    // TODO: Mock out the backing store
  }
}
*/