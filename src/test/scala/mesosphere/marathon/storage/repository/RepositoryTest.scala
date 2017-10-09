package mesosphere.marathon
package storage.repository

import java.util.UUID

import akka.Done
import com.codahale.metrics.MetricRegistry
import com.twitter.zk.ZNode
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.repository.{ Repository, VersionedRepository }
import mesosphere.marathon.core.storage.store.impl.cache.{ LazyCachingPersistenceStore, LazyVersionCachingPersistentStore, LoadTimeCachingPersistenceStore }
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.ZkPersistenceStore
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp, VersionInfo }
import mesosphere.marathon.storage.repository.legacy.store.{ CompressionConf, EntityStore, InMemoryStore, MarathonStore, ZKStore }
import mesosphere.marathon.stream.Sink
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.concurrent.duration._

class RepositoryTest extends AkkaUnitTest with ZookeeperServerTest with GivenWhenThen {
  import PathId._

  def randomAppId = UUID.randomUUID().toString.toRootPath
  def randomApp = AppDefinition(randomAppId, versionInfo = VersionInfo.OnlyVersion(Timestamp.now()))

  def basic(name: String, createRepo: (Int) => Repository[PathId, AppDefinition]): Unit = {
    s"$name:unversioned" should {
      "get of a non-existent value should return nothing" in {
        val repo = createRepo(0)
        repo.get(randomAppId).futureValue should be('empty)
      }
      "delete should be idempotent" in {
        val repo = createRepo(0)
        val id = randomAppId
        repo.delete(id).futureValue should be(Done)
        repo.delete(id).futureValue should be(Done)
      }
      "ids should return nothing" in {
        val repo = createRepo(0)
        repo.ids().runWith(Sink.seq).futureValue should be('empty)
      }
      "retrieve the previously stored value for two keys" in {
        val repo = createRepo(0)
        val app1 = randomApp
        val app2 = randomApp

        repo.store(app1).futureValue
        repo.store(app2).futureValue

        repo.get(app1.id).futureValue.value should equal(app1)
        repo.get(app2.id).futureValue.value should equal(app2)
      }
      "store with the same id should update the object" in {
        val repo = createRepo(0)
        val start = randomApp
        val end = start.copy(cmd = Some("abcd"))

        repo.store(start).futureValue
        repo.store(end).futureValue

        repo.get(end.id).futureValue.value should equal(end)
        repo.get(start.id).futureValue.value should equal(end)
        repo.all().runWith(Sink.seq).futureValue should equal(Seq(end))
      }
      "stored objects should list in the ids and all" in {
        val repo = createRepo(0)
        val app1 = randomApp
        val app2 = randomApp

        Given("Two objects")
        repo.store(app1).futureValue
        repo.store(app2).futureValue

        Then("They should list in the ids and all")
        repo.ids().runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(app1.id, app2.id)
        repo.all().runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(app1, app2)

        When("one of them is removed")
        repo.delete(app2.id).futureValue

        Then("it should no longer be in the ids")
        repo.ids().runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(app1.id)
        repo.all().runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(app1)
      }
    }
  }

  def versioned(name: String, createRepo: (Int) => VersionedRepository[PathId, AppDefinition]): Unit = {
    s"$name:versioned" should {
      "list no versions when empty" in {
        val repo = createRepo(2)
        repo.versions(randomAppId).runWith(Sink.seq).futureValue should be('empty)
      }
      "list and retrieve the current and all previous versions up to the cap" in {
        val repo = createRepo(3)
        val app = randomApp.copy(versionInfo = VersionInfo.OnlyVersion(Timestamp(1)))
        val lastVersion = app.copy(versionInfo = VersionInfo.OnlyVersion(Timestamp(4)))
        // two previous versions and current (so app is gone)
        val versions = Seq(
          app,
          app.copy(versionInfo = VersionInfo.OnlyVersion(Timestamp(2))),
          app.copy(versionInfo = VersionInfo.OnlyVersion(Timestamp(3))),
          lastVersion)
        versions.foreach { v => repo.store(v).futureValue }

        // New Persistence Stores are Garbage collected so they can store extra versions...
        versions.tail.map(_.version.toOffsetDateTime).toSet.diff(
          repo.versions(app.id).runWith(Sink.set).futureValue) should be ('empty)
        versions.tail.toSet.diff(repo.versions(app.id).mapAsync(Int.MaxValue)(repo.getVersion(app.id, _))
          .collect { case Some(g) => g }
          .runWith(Sink.set).futureValue) should be ('empty)

        repo.get(app.id).futureValue.value should equal(lastVersion)

        When("deleting the current version")
        repo.deleteCurrent(app.id).futureValue

        Then("The versions are still list-able, including the current one")
        versions.tail.map(_.version.toOffsetDateTime).toSet.diff(
          repo.versions(app.id).runWith(Sink.set).futureValue) should be('empty)
        versions.tail.toSet.diff(
          repo.versions(app.id).mapAsync(Int.MaxValue)(repo.getVersion(app.id, _))
          .collect { case Some(g) => g }
          .runWith(Sink.set).futureValue
        ) should be ('empty)

        And("Get of the current will fail")
        repo.get(app.id).futureValue should be('empty)

        When("deleting all")
        repo.delete(app.id).futureValue

        Then("No versions remain")
        repo.versions(app.id).runWith(Sink.seq).futureValue should be('empty)
      }
      "be able to store a specific version" in {
        val repo = createRepo(2)
        val app = randomApp
        repo.storeVersion(app).futureValue

        repo.versions(app.id).runWith(Sink.seq).futureValue should
          contain theSameElementsAs Seq(app.version.toOffsetDateTime)
        repo.get(app.id).futureValue should be ('empty)
        repo.getVersion(app.id, app.version.toOffsetDateTime).futureValue.value should equal(app)
      }
    }
  }

  def createLegacyInMemoryRepo(maxVersions: Int): AppRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = new InMemoryStore()
    def entityStore(name: String, newState: () => AppDefinition): EntityStore[AppDefinition] = {
      val marathonStore = new MarathonStore(store, metrics, newState, name)
      marathonStore.markOpen()
      marathonStore
    }
    AppRepository.legacyRepository(entityStore, maxVersions)
  }

  def createLegacyZkRepo(maxVersions: Int): AppRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val client = twitterZkClient()
    val store = new ZKStore(client, ZNode(client, s"/${UUID.randomUUID().toString}"),
      CompressionConf(true, 64 * 1024), 8, 1024)
    def entityStore(name: String, newState: () => AppDefinition): EntityStore[AppDefinition] = {
      val marathonStore = new MarathonStore(store, metrics, newState, name)
      marathonStore.markOpen()
      store.initialize().futureValue(Timeout(5.seconds))
      marathonStore
    }
    AppRepository.legacyRepository(entityStore, maxVersions)
  }

  def createInMemRepo(maxVersions: Int): AppRepository = { // linter:ignore:UnusedParameter
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = new InMemoryPersistenceStore()
    store.markOpen()
    AppRepository.inMemRepository(store)
  }

  def createLoadTimeCachingRepo(maxVersions: Int): AppRepository = { // linter:ignore:UnusedParameter
    implicit val metrics = new Metrics(new MetricRegistry)
    val cached = new LoadTimeCachingPersistenceStore(new InMemoryPersistenceStore())
    cached.markOpen()
    cached.preDriverStarts.futureValue
    AppRepository.inMemRepository(cached)
  }

  def createZKRepo(maxVersions: Int): AppRepository = { // linter:ignore:UnusedParameter
    implicit val metrics = new Metrics(new MetricRegistry)
    val root = UUID.randomUUID().toString
    val rootClient = zkClient(namespace = Some(root))
    val store = new ZkPersistenceStore(rootClient, Duration.Inf)
    store.markOpen()
    AppRepository.zkRepository(store)
  }

  def createLazyCachingRepo(maxVersions: Int): AppRepository = { // linter:ignore:UnusedParameter
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = LazyCachingPersistenceStore(new InMemoryPersistenceStore())
    store.markOpen()
    AppRepository.inMemRepository(store)
  }

  def createLazyVersionCachingRepo(maxVersions: Int): AppRepository = { // linter:ignore:UnusedParameter
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = LazyVersionCachingPersistentStore(new InMemoryPersistenceStore())
    store.markOpen()
    AppRepository.inMemRepository(store)
  }

  behave like basic("InMemEntity", createLegacyInMemoryRepo)
  behave like basic("ZkEntity", createLegacyZkRepo)
  behave like basic("InMemoryPersistence", createInMemRepo)
  behave like basic("ZkPersistence", createZKRepo)
  behave like basic("LoadTimeCachingPersistence", createLoadTimeCachingRepo)
  behave like basic("LazyCachingPersistence", createLazyCachingRepo)

  behave like versioned("InMemEntity", createLegacyInMemoryRepo)
  behave like versioned("ZkEntity", createLegacyZkRepo)
  behave like versioned("InMemoryPersistence", createInMemRepo)
  behave like versioned("ZkPersistence", createZKRepo)
  behave like versioned("LoadTimeCachingPersistence", createLoadTimeCachingRepo)
  behave like versioned("LazyCachingPersistence", createLazyCachingRepo)
  behave like versioned("LazyVersionCachingPersistence", createLazyVersionCachingRepo)
}
