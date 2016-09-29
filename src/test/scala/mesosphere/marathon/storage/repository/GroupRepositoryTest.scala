package mesosphere.marathon.storage.repository

import java.time.OffsetDateTime
import java.util.UUID

import akka.Done
import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import com.twitter.zk.ZNode
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.store.impl.cache.{ LazyCachingPersistenceStore, LoadTimeCachingPersistenceStore }
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.ZkPersistenceStore
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, Timestamp }
import mesosphere.marathon.storage.repository.legacy.GroupEntityRepository
import mesosphere.marathon.storage.repository.legacy.store.{ CompressionConf, EntityStore, InMemoryStore, MarathonStore, ZKStore }
import mesosphere.marathon.test.Mockito
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, _ }

class GroupRepositoryTest extends AkkaUnitTest with Mockito with ZookeeperServerTest {
  import PathId._

  def basicGroupRepository[K, C, S](name: String, createRepo: (AppRepository, PodRepository, Int) => GroupRepository): Unit = {
    name should {
      "return an empty root if no root exists" in {
        val repo = createRepo(mock[AppRepository], mock[PodRepository], 1)
        val root = repo.root().futureValue
        root.transitiveAppsById should be('empty)
        root.dependencies should be('empty)
        root.groups should be('empty)
      }
      "have no versions" in {
        val repo = createRepo(mock[AppRepository], mock[PodRepository], 1)
        repo.rootVersions().runWith(Sink.seq).futureValue should be('empty)
      }
      "not be able to get historical versions" in {
        val repo = createRepo(mock[AppRepository], mock[PodRepository], 1)
        repo.rootVersion(OffsetDateTime.now).futureValue should not be ('defined)
      }
      "store and retrieve the empty group" in {
        val repo = createRepo(mock[AppRepository], mock[PodRepository], 1)
        val root = repo.root().futureValue
        repo.storeRoot(root, Nil, Nil, Nil, Nil).futureValue
        repo.root().futureValue should be(root)
        root.id should be ('empty)
      }
      "store new apps when storing the root" in {
        val appRepo = mock[AppRepository]
        val repo = createRepo(appRepo, mock[PodRepository], 1)
        val apps = Seq(AppDefinition("app1".toRootPath), AppDefinition("app2".toRootPath))
        val root = repo.root().futureValue

        val newRoot = root.copy(apps = apps.map(app => app.id -> app)(collection.breakOut))

        appRepo.store(any) returns Future.successful(Done)

        repo.storeRoot(root, apps, Nil, Nil, Nil).futureValue
        repo.root().futureValue should equal(newRoot)
        newRoot.id should be ('empty)

        verify(appRepo).store(apps.head)
        verify(appRepo).store(apps.tail.head)
        noMoreInteractions(appRepo)
      }
      "not store the group if updating apps fails" in {
        val appRepo = mock[AppRepository]
        val repo = createRepo(appRepo, mock[PodRepository], 1)
        val apps = Seq(AppDefinition("app1".toRootPath), AppDefinition("app2".toRootPath))
        val root = repo.root().futureValue
        repo.storeRoot(root, Nil, Nil, Nil, Nil).futureValue

        val newRoot = root.copy(apps = apps.map(app => app.id -> app)(collection.breakOut))

        val exception = new Exception("App Store Failed")
        appRepo.store(any) returns Future.failed(exception)

        repo.storeRoot(newRoot, apps, Nil, Nil, Nil).failed.futureValue should equal(exception)
        repo.root().futureValue should equal(root)

        repo match {
          case s: StoredGroupRepositoryImpl[_, _, _] =>
            s.underlyingRoot().futureValue should equal(root)
          case s: GroupEntityRepository =>
            s.store.fetch(GroupEntityRepository.ZkRootName.safePath).futureValue.value should equal(root)
        }

        verify(appRepo).store(apps.head)
        verify(appRepo).store(apps.tail.head)
        noMoreInteractions(appRepo)
      }
      "store the group if deleting apps fails" in {
        val appRepo = mock[AppRepository]
        val repo = createRepo(appRepo, mock[PodRepository], 1)
        val app1 = AppDefinition("app1".toRootPath)
        val app2 = AppDefinition("app2".toRootPath)
        val apps = Seq(app1, app2)
        val root = repo.root().futureValue
        repo.storeRoot(root, Nil, Nil, Nil, Nil).futureValue
        val deleted = "deleteMe".toRootPath

        val newRoot = root.copy(apps = apps.map(app => app.id -> app)(collection.breakOut))

        val exception = new Exception("App Delete Failed")
        appRepo.store(any) returns Future.successful(Done)
        // The legacy repos call delete, the new ones call deleteCurrent
        appRepo.deleteCurrent(deleted) returns Future.failed(exception)
        appRepo.delete(deleted) returns Future.failed(exception)

        appRepo.getVersion(app1.id, app1.version.toOffsetDateTime) returns Future.successful(Some(app1))
        appRepo.getVersion(app2.id, app2.version.toOffsetDateTime) returns Future.successful(Some(app2))

        repo.storeRoot(newRoot, apps, Seq(deleted), Nil, Nil).futureValue
        repo.root().futureValue should equal(newRoot)

        verify(appRepo).store(apps.head)
        verify(appRepo).store(apps.tail.head)
        verify(appRepo, atMost(1)).deleteCurrent(deleted)
        verify(appRepo, atMost(1)).delete(deleted)
        verify(appRepo, atMost(1)).getVersion(app1.id, app1.version.toOffsetDateTime)
        verify(appRepo, atMost(1)).getVersion(app2.id, app2.version.toOffsetDateTime)
        noMoreInteractions(appRepo)
      }
      "retrieve a historical version" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val appRepo = AppRepository.inMemRepository(new InMemoryPersistenceStore())
        val repo = createRepo(appRepo, mock[PodRepository], 2)

        val app1 = AppDefinition("app1".toRootPath)
        val app2 = AppDefinition("app2".toRootPath)

        val initialRoot = repo.root().futureValue
        val firstRoot = initialRoot.copy(apps = Map(app1.id -> app1))
        repo.storeRoot(firstRoot, Seq(app1), Nil, Nil, Nil).futureValue

        val nextRoot = initialRoot.copy(apps = Map(app2.id -> app2), version = Timestamp(1))
        repo.storeRoot(nextRoot, Seq(app2), Seq(app1.id), Nil, Nil).futureValue

        repo.rootVersion(firstRoot.version.toOffsetDateTime).futureValue.value should equal(firstRoot)
        repo.rootVersions().runWith(Sink.seq).futureValue should contain theSameElementsAs
          Seq(firstRoot.version.toOffsetDateTime, nextRoot.version.toOffsetDateTime)
        repo.rootVersions().mapAsync(Int.MaxValue)(repo.rootVersion)
          .collect { case Some(g) => g }.runWith(Sink.seq).futureValue should contain theSameElementsAs
          Seq(firstRoot, nextRoot)
      }
    }
  }

  def createInMemRepos(appRepository: AppRepository, podRepository: PodRepository, maxVersions: Int): GroupRepository = { // linter:ignore:UnusedParameter
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = new InMemoryPersistenceStore()
    GroupRepository.inMemRepository(store, appRepository, podRepository)
  }

  private def zkStore: ZkPersistenceStore = {
    val root = UUID.randomUUID().toString
    val rootClient = zkClient(namespace = Some(root))
    implicit val metrics = new Metrics(new MetricRegistry)
    new ZkPersistenceStore(rootClient, Duration.Inf)
  }

  def createZkRepos(appRepository: AppRepository, podRepository: PodRepository, maxVersions: Int): GroupRepository = { // linter:ignore:UnusedParameter
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = zkStore
    GroupRepository.zkRepository(store, appRepository, podRepository)
  }

  def createLazyCachingRepos(appRepository: AppRepository, podRepository: PodRepository, maxVersions: Int): GroupRepository = { // linter:ignore:UnusedParameter
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = new LazyCachingPersistenceStore(new InMemoryPersistenceStore())
    GroupRepository.inMemRepository(store, appRepository, podRepository)
  }

  def createLoadCachingRepos(appRepository: AppRepository, podRepository: PodRepository, maxVersions: Int): GroupRepository = { // linter:ignore:UnusedParameter
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = new LoadTimeCachingPersistenceStore(new InMemoryPersistenceStore())
    store.preDriverStarts.futureValue
    GroupRepository.inMemRepository(store, appRepository, podRepository)
  }

  def createLegacyInMemRepos(appRepository: AppRepository, podRepository: PodRepository, maxVersions: Int): GroupRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val persistentStore = new InMemoryStore()
    def entityStore(name: String, newState: () => Group): EntityStore[Group] = {
      new MarathonStore(persistentStore, metrics, newState, name)
    }
    GroupRepository.legacyRepository(entityStore, maxVersions, appRepository, podRepository)
  }

  def createLegacyZkRepos(appRepository: AppRepository, podRepository: PodRepository, maxVersions: Int): GroupRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val client = twitterZkClient()
    val persistentStore = new ZKStore(client, ZNode(client, s"/${UUID.randomUUID().toString}"),
      CompressionConf(true, 64 * 1024), 8, 1024)
    persistentStore.initialize().futureValue(Timeout(5.seconds))
    def entityStore(name: String, newState: () => Group): EntityStore[Group] = {
      new MarathonStore(persistentStore, metrics, newState, name)
    }
    GroupRepository.legacyRepository(entityStore, maxVersions, appRepository, podRepository)
  }

  behave like basicGroupRepository("InMemory", createInMemRepos)
  behave like basicGroupRepository("Zk", createZkRepos)
  behave like basicGroupRepository("LazyCaching", createLazyCachingRepos)
  behave like basicGroupRepository("LoadCaching", createLoadCachingRepos)
  behave like basicGroupRepository("LegacyInMem", createLegacyInMemRepos)
  behave like basicGroupRepository("LegacyZk", createLegacyZkRepos)
}

