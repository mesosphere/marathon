package mesosphere.marathon.storage.repository

import java.util.UUID

import akka.Done
import com.codahale.metrics.MetricRegistry
import com.twitter.zk.ZNode
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.repository.SingletonRepository
import mesosphere.marathon.core.storage.store.impl.cache.{ LazyCachingPersistenceStore, LoadTimeCachingPersistenceStore }
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.ZkPersistenceStore
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.storage.repository.legacy.store.{ CompressionConf, EntityStore, InMemoryStore, MarathonStore, ZKStore }
import mesosphere.util.state.FrameworkId
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.concurrent.duration._

class SingletonRepositoryTest extends AkkaUnitTest with ZookeeperServerTest {
  def basic(name: String, createRepo: => SingletonRepository[FrameworkId]): Unit = {
    name should {
      "return none if nothing has been stored" in {
        val repo = createRepo
        repo.get().futureValue should be ('empty)
      }
      "delete should succeed if nothing has been stored" in {
        val repo = createRepo
        repo.delete().futureValue should be(Done)
      }
      "retrieve the previously stored value" in {
        val repo = createRepo
        val id = FrameworkId(UUID.randomUUID().toString)
        repo.store(id).futureValue
        repo.get().futureValue.value should equal(id)
      }
      "delete a previously stored value should unset the value" in {
        val repo = createRepo
        val id = FrameworkId(UUID.randomUUID().toString)
        repo.store(id).futureValue
        repo.delete().futureValue should be(Done)
        repo.get().futureValue should be ('empty)
      }
    }
  }

  def createLegacyInMemoryRepo(): FrameworkIdRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = new InMemoryStore()
    def entityStore(name: String, newState: () => FrameworkId): EntityStore[FrameworkId] = {
      val marathonStore = new MarathonStore(store, metrics, newState, name)
      marathonStore.markOpen()
      marathonStore
    }
    FrameworkIdRepository.legacyRepository(entityStore)
  }

  def createLegacyZkRepo(): FrameworkIdRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val client = twitterZkClient()
    val store = new ZKStore(client, ZNode(client, s"/${UUID.randomUUID().toString}"),
      CompressionConf(true, 64 * 1024), 8, 1024)
    def entityStore(name: String, newState: () => FrameworkId): EntityStore[FrameworkId] = {
      val marathonStore = new MarathonStore(store, metrics, newState, name)
      marathonStore.markOpen()
      store.initialize().futureValue(Timeout(5.seconds))
      marathonStore
    }
    FrameworkIdRepository.legacyRepository(entityStore)
  }

  def createInMemRepo(): FrameworkIdRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = new InMemoryPersistenceStore()
    store.markOpen()
    FrameworkIdRepository.inMemRepository(store)
  }

  def createLoadTimeCachingRepo(): FrameworkIdRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val cached = new LoadTimeCachingPersistenceStore(new InMemoryPersistenceStore())
    cached.markOpen()
    cached.preDriverStarts.futureValue
    FrameworkIdRepository.inMemRepository(cached)
  }

  def createZKRepo(): FrameworkIdRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = new ZkPersistenceStore(zkClient(), 10.seconds)
    store.markOpen()
    FrameworkIdRepository.zkRepository(store)
  }

  def createLazyCachingRepo(): FrameworkIdRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = LazyCachingPersistenceStore(new InMemoryPersistenceStore())
    store.markOpen()
    FrameworkIdRepository.inMemRepository(store)
  }

  behave like basic("InMemEntity", createLegacyInMemoryRepo())
  behave like basic("ZkEntity", createLegacyZkRepo())
  behave like basic("InMemoryPersistence", createInMemRepo())
  behave like basic("ZkPersistence", createZKRepo())
  behave like basic("LoadTimeCachingPersistence", createLoadTimeCachingRepo())
  behave like basic("LazyCachingPersistence", createLazyCachingRepo())
}
