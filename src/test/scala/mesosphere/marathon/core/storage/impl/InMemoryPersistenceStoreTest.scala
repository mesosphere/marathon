package mesosphere.marathon.core.storage.impl

import java.time.OffsetDateTime

import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.{ IdResolver, PersistenceStoreTest, TestClass1 }
import mesosphere.marathon.metrics.Metrics

trait InMemoryTestClass1Serialization {
  implicit val tc1IdResolver: IdResolver[String, RamId, String, TestClass1, Identity] =
    new IdResolver[String, RamId, String, TestClass1, Identity] {
      override def toStorageId(id: String, version: Option[OffsetDateTime]): RamId =
        RamId(category, id, version)
      override val category: String = "test-class"
      override def fromStorageId(key: RamId): String = key.id
      override val maxVersions: Int = 2
      override def version(tc: TestClass1) = tc.version
    }
}

class InMemoryPersistenceStoreTest extends AkkaUnitTest with PersistenceStoreTest
    with InMemoryStoreSerialization with InMemoryTestClass1Serialization {

  implicit val metrics = new Metrics(new MetricRegistry)

  "InMemoryPersistenceStore" should {
    behave like emptyPersistenceStore(new InMemoryPersistenceStore())
  }
}
