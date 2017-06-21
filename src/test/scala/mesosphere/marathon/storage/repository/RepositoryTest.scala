package mesosphere.marathon
package storage.repository

import java.util.UUID

import akka.Done
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.repository.{ Repository, VersionedRepository }
import mesosphere.marathon.core.storage.store.impl.cache.{ LazyCachingPersistenceStore, LazyVersionCachingPersistentStore, LoadTimeCachingPersistenceStore }
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.ZkPersistenceStore
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp, VersionInfo }
import mesosphere.marathon.stream.Sink
import org.scalatest.GivenWhenThen
import org.scalatest.time.{ Seconds, Span }

import scala.concurrent.duration._

class RepositoryTest extends AkkaUnitTest with ZookeeperServerTest with GivenWhenThen {
  import PathId._

  def randomAppId = UUID.randomUUID().toString.toRootPath
  def randomApp = AppDefinition(randomAppId, versionInfo = VersionInfo.OnlyVersion(Timestamp.now()))

  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(30, Seconds))

  def basic(name: String, createRepo: () => Repository[PathId, AppDefinition]): Unit = {
    s"$name:unversioned" should {
      "get of a non-existent value should return nothing" in {
        val repo = createRepo()
        repo.get(randomAppId).futureValue should be('empty)
      }
      "delete should be idempotent" in {
        val repo = createRepo()
        val id = randomAppId
        repo.delete(id).futureValue should be(Done)
        repo.delete(id).futureValue should be(Done)
      }
      "ids should return nothing" in {
        val repo = createRepo()
        repo.ids().runWith(Sink.seq).futureValue should be('empty)
      }
      "retrieve the previously stored value for two keys" in {
        val repo = createRepo()
        val app1 = randomApp
        val app2 = randomApp

        repo.store(app1).futureValue
        repo.store(app2).futureValue

        repo.get(app1.id).futureValue.value should equal(app1)
        repo.get(app2.id).futureValue.value should equal(app2)
      }
      "store with the same id should update the object" in {
        val repo = createRepo()
        val start = randomApp
        val end = start.copy(cmd = Some("abcd"))

        repo.store(start).futureValue
        repo.store(end).futureValue

        repo.get(end.id).futureValue.value should equal(end)
        repo.get(start.id).futureValue.value should equal(end)
        repo.all().runWith(Sink.seq).futureValue should equal(Seq(end))
      }
      "stored objects should list in the ids and all" in {
        val repo = createRepo()
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

  def versioned(name: String, createRepo: () => VersionedRepository[PathId, AppDefinition]): Unit = {
    s"$name:versioned" should {
      "list no versions when empty" in {
        val repo = createRepo()
        repo.versions(randomAppId).runWith(Sink.seq).futureValue should be('empty)
      }
      "list and retrieve the current and all previous versions up to the cap" in {
        val repo = createRepo()
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
        val repo = createRepo()
        val app = randomApp
        repo.storeVersion(app).futureValue

        repo.versions(app.id).runWith(Sink.seq).futureValue should
          contain theSameElementsAs Seq(app.version.toOffsetDateTime)
        repo.get(app.id).futureValue should be ('empty)
        repo.getVersion(app.id, app.version.toOffsetDateTime).futureValue.value should equal(app)
      }
    }
  }

  def createInMemRepo(): AppRepository = {
    AppRepository.inMemRepository(new InMemoryPersistenceStore())
  }

  def createLoadTimeCachingRepo(): AppRepository = {
    val cached = new LoadTimeCachingPersistenceStore(new InMemoryPersistenceStore())
    cached.preDriverStarts.futureValue
    AppRepository.inMemRepository(cached)
  }

  def createZKRepo(): AppRepository = {
    val root = UUID.randomUUID().toString
    val rootClient = zkClient(namespace = Some(root))
    val store = new ZkPersistenceStore(rootClient, Duration.Inf)
    AppRepository.zkRepository(store)
  }

  def createLazyCachingRepo(): AppRepository = {
    AppRepository.inMemRepository(LazyCachingPersistenceStore(new InMemoryPersistenceStore()))
  }

  def createLazyVersionCachingRepo(): AppRepository = {
    AppRepository.inMemRepository(LazyVersionCachingPersistentStore(new InMemoryPersistenceStore()))
  }

  behave like basic("InMemoryPersistence", createInMemRepo)
  behave like basic("ZkPersistence", createZKRepo)
  behave like basic("LoadTimeCachingPersistence", createLoadTimeCachingRepo)
  behave like basic("LazyCachingPersistence", createLazyCachingRepo)

  behave like versioned("InMemoryPersistence", createInMemRepo)
  behave like versioned("ZkPersistence", createZKRepo)
  behave like versioned("LoadTimeCachingPersistence", createLoadTimeCachingRepo)
  behave like versioned("LazyCachingPersistence", createLazyCachingRepo)
  behave like versioned("LazyVersionCachingPersistence", createLazyVersionCachingRepo)
}
