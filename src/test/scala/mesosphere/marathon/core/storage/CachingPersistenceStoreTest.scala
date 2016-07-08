package mesosphere.marathon.core.storage

import java.util.UUID

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.impl.{
CachingPersistenceStore, InMemoryPersistenceStore, InMemoryStoreSerialization, RamId}

class CachingPersistenceStoreTest extends AkkaUnitTest
    with PersistenceStoreTest /*with ZookeeperServerTest */ with TestClass1Implicits
    with InMemoryStoreSerialization {
  val rootId = ""
  def createId: String = s"${UUID.randomUUID().toString}"

  implicit val scheduler = system.scheduler

  /*
  lazy val zkStore: ZkPersistenceStore = {
    val client = zkClient()
    val root = createId
    client.create(s"/$root").futureValue
    new ZkPersistenceStore(client.usingNamespace(root))
  }
*/
  val cachedInMemory = new CachingPersistenceStore(new InMemoryPersistenceStore(),
    (ramId: RamId) => ramId.id,
    (id: String) => RamId(id))
  /*lazy val cachedZk = new CachingPersistenceStore(zkStore,
    (zkId: ZkId) => zkId.id,
    (id: String) => ZkId(id))
*/
  "CachingPersistenceStore" when {
    "backed by InMemoryPersistenceStore" should {
      cachedInMemory.preDriverStarts
      behave like singleTypeStore(cachedInMemory)
    }
    /*
    "backed by ZkPersistenceStore" should {
      cachedZk.preDriverStarts
      behave like singleTypeStore(cachedZk)
    }*/
    // TODO: Mock out the store
  }
}
