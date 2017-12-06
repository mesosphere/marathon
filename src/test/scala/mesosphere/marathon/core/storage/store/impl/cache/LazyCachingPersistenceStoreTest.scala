package mesosphere.marathon
package core.storage.store.impl.cache

import java.util.UUID

import akka.Done
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.Sink
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.store.impl.InMemoryTestClass1Serialization
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.{ ZkPersistenceStore, ZkTestClass1Serialization }
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStoreTest, TestClass1 }
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.storage.store.InMemoryStoreSerialization
import mesosphere.marathon.test.SettableClock

import scala.concurrent.duration._

class LazyCachingPersistenceStoreTest extends AkkaUnitTest
  with PersistenceStoreTest with ZkTestClass1Serialization with ZookeeperServerTest
  with InMemoryStoreSerialization with InMemoryTestClass1Serialization {

  private def cachedInMemory = {
    val store = LazyCachingPersistenceStore(new InMemoryPersistenceStore())
    store.markOpen()
    store
  }

  private def withLazyVersionCaching = {
    val store = LazyVersionCachingPersistentStore(new InMemoryPersistenceStore())
    store.markOpen()
    store
  }

  private def cachedZk = {
    val root = UUID.randomUUID().toString
    val client = zkClient(namespace = Some(root))
    val store = LazyCachingPersistenceStore(new ZkPersistenceStore(client, Duration.Inf, 8))
    store.markOpen()
    store
  }

  behave like basicPersistenceStore("LazyCache(InMemory)", cachedInMemory)
  behave like basicPersistenceStore("LazyCache(Zk)", cachedZk)
  behave like basicPersistenceStore("LazyVersionedCache(Zk)", withLazyVersionCaching)

  // TODO: Mock out the backing store.

  behave like cachingPersistenceStore("cache internals(InMemory)", withLazyVersionCaching)

  def cachingPersistenceStore[K, C, Serialized](
    name: String,
    newStore: => LazyVersionCachingPersistentStore[K, C, Serialized])(
    implicit
    ir: IdResolver[String, TestClass1, C, K],
    m: Marshaller[TestClass1, Serialized],
    um: Unmarshaller[Serialized, TestClass1]): Unit = {

    name should {
      "purge the cache appropriately" in {
        implicit val clock = new SettableClock()
        val store = newStore
        1.to(100).foreach { i =>
          val obj = TestClass1("abc", i)
          clock.plus(1.second)
          store.store("task-1", obj).futureValue should be(Done)
        }
        store.versionedValueCache.size should be(100) // sanity
        store.maybePurgeCachedVersions(maxEntries = 50, purgeCount = 10)
        store.versionedValueCache.size > 40 should be(true)
        store.versionedValueCache.size <= 50 should be(true)
      }
      "caches versions independently" in {
        implicit val clock = new SettableClock()
        val store = newStore
        val original = TestClass1("abc", 1)
        clock.plus(1.minute)
        val updated = TestClass1("def", 2)
        store.store("task-1", original).futureValue should be(Done)
        store.store("task-1", updated).futureValue should be(Done)
        store.store("task-1", updated).futureValue should be(Done) // redundant store should not lead to dup data

        val storageId = ir.toStorageId("task-1", None)
        val cacheKey = (ir.category, storageId)

        store.versionedValueCache.size should be(2)
        store.versionedValueCache((storageId, original.version)) should be(Some(original))
        store.versionedValueCache((storageId, updated.version)) should be(Some(updated))
      }

      "invalidates all cached versions upon deletion" in {
        implicit val clock = new SettableClock()
        val store = newStore
        val original = TestClass1("abc", 1)
        clock.plus(1.minute)
        val updated = TestClass1("def", 2)
        store.store("task-1", original).futureValue should be(Done)
        store.store("task-1", updated).futureValue should be(Done)
        store.deleteVersion("task-1", original.version).futureValue should be(Done)

        val storageId = ir.toStorageId("task-1", None)
        val cacheKey = (ir.category, storageId)

        store.versionCache.size should be(0)
        store.versionedValueCache.size should be(0)
      }

      "reload versionCache upon versions request" in {
        implicit val clock = new SettableClock()
        val store = newStore
        val original = TestClass1("abc", 1)
        clock.plus(1.minute)
        val updated = TestClass1("def", 2)
        store.store("task-1", original).futureValue should be(Done)
        store.store("task-1", updated).futureValue should be(Done)

        store.versionCache.clear()
        store.versionedValueCache.clear()

        store.versions("task-1").runWith(Sink.seq).futureValue should contain
        theSameElementsAs(Seq(original.version, updated.version))

        store.versionedValueCache.size should be(0)
      }

      "reload versionedValueCache upon versioned get requests" in {
        implicit val clock = new SettableClock()
        val store = newStore
        val original = TestClass1("abc", 1)
        clock.plus(1.minute)
        val updated = TestClass1("def", 2)
        store.store("task-1", original).futureValue should be(Done)
        store.store("task-1", updated).futureValue should be(Done)

        store.versionCache.clear()
        store.versionedValueCache.clear()

        store.get("task-1", original.version).futureValue should be(Some(original)) // sanity check

        val storageId = ir.toStorageId("task-1", None)

        store.versionedValueCache.size should be(1)
        store.versionedValueCache.contains((storageId, original.version)) should be(true)

        store.versionCache.size should be(0)
      }

      "reload versionedValueCache upon unversioned get requests" in {
        implicit val clock = new SettableClock()
        val store = newStore
        val original = TestClass1("abc", 1)
        clock.plus(1.minute)
        val updated = TestClass1("def", 2)
        store.store("task-1", original).futureValue should be(Done)
        store.store("task-1", updated).futureValue should be(Done)

        store.versionCache.clear()
        store.versionedValueCache.clear()

        store.get("task-1").futureValue should be(Some(updated)) // sanity check

        val storageId = ir.toStorageId("task-1", None)

        store.versionedValueCache.size should be(1)
        store.versionedValueCache.contains((storageId, updated.version)) should be(true)
      }

      "versions available in the persistence store are cached correctly" in {
        implicit val clock = new SettableClock()
        val store = newStore
        val underlying = store.store

        // 1 version available in the cache and 2 in the underlying store
        store.store("test", TestClass1("abc", 1)).futureValue should be(Done)
        clock.plus(1.minute)
        underlying.store("test", TestClass1("abc", 2)).futureValue should be(Done)
        clock.plus(1.minute)
        underlying.store("test", TestClass1("abc", 3)).futureValue should be(Done)

        store.versionCache.size should be(0)
        // a call to versions will update the cache
        store.versions("test").runWith(Sink.seq).futureValue should have size 3
        store.versionCache should have size 1
        store.versionCache((ir.category, ir.toStorageId("test", None))) should have size 3
      }
    }
  }

}
