package mesosphere.marathon.state

import java.time.OffsetDateTime
import java.util.UUID

import akka.Done
import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.repository.impl.legacy.store.{ EntityStore, InMemoryStore, MarathonStore }
import mesosphere.marathon.core.storage.repository.{ AppRepository, GroupRepository }
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.storage.store.impl.zk.ZkPersistenceStore
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.test.Mockito

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

trait GroupRepositoryTest extends Mockito { self: AkkaUnitTest =>
  import PathId._

  def basicGroupRepository(name: String, createRepo: (AppRepository, Int) => GroupRepository): Unit = {
    name should {
      "return an empty root if no root exists" in {
        val repo = createRepo(mock[AppRepository], 1)
        val root = repo.root().futureValue
        root.transitiveApps should be ('empty)
        root.dependencies should be ('empty)
        root.groups should be ('empty)
      }
      "have no versions" in {
        val repo = createRepo(mock[AppRepository], 1)
        repo.rootVersions().runWith(Sink.seq).futureValue should be ('empty)
      }
      "not be able to get historical versions" in {
        val repo = createRepo(mock[AppRepository], 1)
        repo.rootVersion(OffsetDateTime.now).futureValue should not be ('defined)
      }
      "store and retrieve the empty group" in {
        val repo = createRepo(mock[AppRepository], 1)
        val root = repo.root().futureValue
        repo.storeRoot(root, Nil, Nil).futureValue
        repo.root().futureValue should be(root)
      }
      "store new apps when storing the root" in {
        val appRepo = mock[AppRepository]
        val repo = createRepo(appRepo, 1)
        val apps = Seq(AppDefinition("app1".toRootPath), AppDefinition("app2".toRootPath))
        val root = repo.root().futureValue

        val newRoot = root.copy(apps = apps.map(app => app.id -> app)(collection.breakOut))

        appRepo.store(any) returns Future.successful(Done)

        repo.storeRoot(root, apps, Nil).futureValue
        repo.root().futureValue should equal(newRoot)

        verify(appRepo).store(apps.head)
        verify(appRepo).store(apps.tail.head)
        noMoreInteractions(appRepo)
      }
      "not store the group if updating apps fails" in {
        val appRepo = mock[AppRepository]
        val repo = createRepo(appRepo, 1)
        val apps = Seq(AppDefinition("app1".toRootPath), AppDefinition("app2".toRootPath))
        val root = repo.root().futureValue
        repo.storeRoot(root, Nil, Nil).futureValue

        val newRoot = root.copy(apps = apps.map(app => app.id -> app)(collection.breakOut))

        val exception = new Exception("App Store Failed")
        appRepo.store(any) returns Future.failed(exception)

        repo.storeRoot(newRoot, apps, Nil).failed.futureValue should equal(exception)
        repo.root().futureValue should equal(root)

        verify(appRepo).store(apps.head)
        verify(appRepo).store(apps.tail.head)
        noMoreInteractions(appRepo)
      }
      "store the group if deleting apps fails" in {
        val appRepo = mock[AppRepository]
        val repo = createRepo(appRepo, 1)
        val app1 = AppDefinition("app1".toRootPath)
        val app2 = AppDefinition("app2".toRootPath)
        val apps = Seq(app1, app2)
        val root = repo.root().futureValue
        repo.storeRoot(root, Nil, Nil).futureValue
        val deleted = "deleteMe".toRootPath

        val newRoot = root.copy(apps = apps.map(app => app.id -> app)(collection.breakOut))

        val exception = new Exception("App Delete Failed")
        appRepo.store(any) returns Future.successful(Done)
        // The legacy repos call delete, the new ones call deleteCurrent
        appRepo.deleteCurrent(deleted) returns Future.failed(exception)
        appRepo.delete(deleted) returns Future.failed(exception)

        appRepo.getVersion(app1.id, app1.version.toOffsetDateTime) returns Future.successful(Some(app1))
        appRepo.getVersion(app2.id, app2.version.toOffsetDateTime) returns Future.successful(Some(app2))

        repo.storeRoot(newRoot, apps, Seq(deleted)).futureValue
        repo.root().futureValue should equal(newRoot)

        verify(appRepo).store(apps.head)
        verify(appRepo).store(apps.tail.head)
        verify(appRepo, atMost(1)).deleteCurrent(deleted)
        verify(appRepo, atMost(1)).delete(deleted)
        verify(appRepo, atMost(1)).getVersion(app1.id, app1.version.toOffsetDateTime)
        verify(appRepo, atMost(1)).getVersion(app2.id, app2.version.toOffsetDateTime)
        noMoreInteractions(appRepo)
      }
    }
  }
}

class InMemGroupRepositoryTest extends AkkaUnitTest with GroupRepositoryTest {

  def createRepos(appRepository: AppRepository, maxVersions: Int): GroupRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = new InMemoryPersistenceStore()
    GroupRepository.inMemRepository(store, appRepository, maxVersions)
  }
  behave like basicGroupRepository("InMemoryGroupRepository", createRepos)
}

class LegacyGroupRepository extends AkkaUnitTest with GroupRepositoryTest {
  def createRepos(appRepository: AppRepository, maxVersions: Int): GroupRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = new InMemoryStore
    def entityStore[T <: MarathonState[_, T]](
      prefix: String,
      newState: () => T)(implicit ct: ClassTag[T]): EntityStore[T] = {
      new MarathonStore[T](store, metrics, newState, prefix)
    }
    GroupRepository.legacyRepository(entityStore[Group], maxVersions, appRepository)
  }
  behave like basicGroupRepository("LegacyGroupRepository", createRepos)
}

class ZkGroupRepository extends AkkaUnitTest with GroupRepositoryTest with ZookeeperServerTest {
  lazy val rootClient = zkClient()
  private def defaultStore: ZkPersistenceStore = {
    val root = UUID.randomUUID().toString
    rootClient.create(s"/$root").futureValue
    implicit val metrics = new Metrics(new MetricRegistry)
    new ZkPersistenceStore(rootClient.usingNamespace(root), Duration.Inf)
  }
  def createRepos(appRepository: AppRepository, maxVersions: Int): GroupRepository = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val store = defaultStore
    GroupRepository.zkRepository(store, appRepository, maxVersions)
  }

  behave like basicGroupRepository("ZkGroupRepository", createRepos)
}