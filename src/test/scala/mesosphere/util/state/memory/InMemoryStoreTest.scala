package mesosphere.util.state.memory

import mesosphere.util.state.PersistentStoreTest
import mesosphere.marathon.IntegrationTest
import mesosphere.marathon.storage.repository.legacy.store.{ InMemoryEntity, InMemoryStore, PersistentStore }

@IntegrationTest
class InMemoryStoreTest extends PersistentStoreTest {

  //
  // See PersistentStoreTests for general store tests
  //

  test("Update an entity will increment the version") {
    persistentStore.create("foo", "Hello".getBytes.toIndexedSeq).futureValue
    val read = fetch("foo").asInstanceOf[Option[InMemoryEntity]]
    read should be('defined)
    read.get.bytes should be("Hello".getBytes)
    val update = persistentStore.update(read.get.withNewContent("Hello again".getBytes.toIndexedSeq)).futureValue.asInstanceOf[InMemoryEntity]
    update.version should be (read.get.version + 1)
  }

  lazy val persistentStore: PersistentStore = {
    val store = new InMemoryStore
    store.markOpen()
    store
  }
}
