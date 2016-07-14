package mesosphere.marathon.core.storage.impl

import java.time.OffsetDateTime

import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.impl.memory.{ InMemoryPersistenceStore, InMemoryStoreSerialization, RamId }
import mesosphere.marathon.core.storage.{ IdResolver, PersistenceStoreTest, TestClass1 }
import mesosphere.marathon.metrics.Metrics

trait InMemoryTestClass1Serialization {
  implicit object InMemTestClass1Resolver extends IdResolver[String, TestClass1, String, RamId] {
    override def toStorageId(id: String, version: Option[OffsetDateTime]): RamId =
      RamId(category, id, version)
    override val category: String = "test-class"
    override val maxVersions: Int = 2

    override def fromStorageId(key: RamId): String = key.id
    override def version(v: TestClass1): OffsetDateTime = v.version
  }
}

class InMemoryPersistenceStoreTest extends AkkaUnitTest with PersistenceStoreTest
    with InMemoryStoreSerialization with InMemoryTestClass1Serialization {

  implicit val metrics = new Metrics(new MetricRegistry)

  behave like basicPersistenceStore("InMemoryPersistenceStore", new InMemoryPersistenceStore())
}
