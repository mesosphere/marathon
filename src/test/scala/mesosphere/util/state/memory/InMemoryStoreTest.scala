package mesosphere.util.state.memory

import mesosphere.util.state.PersistentStoreTest
import org.scalatest.Matchers
import mesosphere.FutureTestSupport._
import mesosphere.marathon.storage.repository.legacy.store.{ InMemoryEntity, InMemoryStore, PersistentStore }

class InMemoryStoreTest extends PersistentStoreTest with Matchers {

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

  lazy val persistentStore: PersistentStore = new InMemoryStore

}

