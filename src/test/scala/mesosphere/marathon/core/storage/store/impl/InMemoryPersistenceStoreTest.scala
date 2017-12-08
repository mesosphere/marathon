package mesosphere.marathon
package core.storage.store.impl

import java.time.OffsetDateTime

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.store.impl.memory.{ InMemoryPersistenceStore, RamId }
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStoreTest, TestClass1 }
import mesosphere.marathon.storage.store.InMemoryStoreSerialization

trait InMemoryTestClass1Serialization {
  implicit object InMemTestClass1Resolver extends IdResolver[String, TestClass1, String, RamId] {
    override def toStorageId(id: String, version: Option[OffsetDateTime]): RamId =
      RamId(category, id, version)
    override val category: String = "test-class"
    override val hasVersions = true

    override def fromStorageId(key: RamId): String = key.id
    override def version(v: TestClass1): OffsetDateTime = v.version
  }
}

class InMemoryPersistenceStoreTest extends AkkaUnitTest with PersistenceStoreTest
  with InMemoryStoreSerialization with InMemoryTestClass1Serialization {

  def inMemoryStore: InMemoryPersistenceStore = {
    val store = new InMemoryPersistenceStore()
    store.markOpen()
    store
  }

  behave like basicPersistenceStore("InMemoryPersistenceStore", inMemoryStore)
  behave like backupRestoreStore("InMemoryPersistenceStore", inMemoryStore)
}
