package mesosphere.util.state.memory

import mesosphere.util.state.{ PersistentStore, PersistentStoreTest }
import org.scalatest.Matchers
import mesosphere.FutureTestSupport._

class InMemoryStoreTest extends PersistentStoreTest with Matchers {

  //
  // See PersistentStoreTests for general store tests
  //

  test("Update an entity will increment the version") {
    persistentStore.create("foo", "Hello".getBytes).futureValue
    val read = fetch("foo").asInstanceOf[Option[InMemoryEntity]]
    read should be('defined)
    read.get.bytes should be("Hello".getBytes)
    val update = persistentStore.update(read.get.withNewContent("Hello again".getBytes)).futureValue.asInstanceOf[InMemoryEntity]
    update.version should be (read.get.version + 1)
  }

  lazy val persistentStore: PersistentStore = new InMemoryStore

}

