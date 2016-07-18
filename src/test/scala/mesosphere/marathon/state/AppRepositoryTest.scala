package mesosphere.marathon.state

import java.util.UUID

import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.storage.impl.zk.ZkPersistenceStore
import mesosphere.marathon.core.storage.repository.AppRepository
import mesosphere.marathon.integration.setup.ZookeeperServerTest
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.util.state.memory.InMemoryStore

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait AppRepositoryTest { this: AkkaUnitTest =>
  implicit val metrics: Metrics = new Metrics(new MetricRegistry)

  def basicTest(newRepo: => AppRepository): Unit = {
    "be able to store and retrieve an app" in {
      val repo = newRepo

      val path = "testApp".toRootPath
      val timestamp = Timestamp.now()
      val appDef = AppDefinition(id = path, versionInfo = AppDefinition.VersionInfo.forNewConfig(timestamp))

      repo.store(appDef).futureValue
      repo.get(appDef.id).futureValue.value should equal(appDef)
    }
    "be able to update an app and keep the old version" in {
      val repo = newRepo
      val path = "testApp".toRootPath
      val appDef = AppDefinition(id = path)
      val nextVersion = appDef.copy(versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(2)))

      repo.store(appDef).futureValue
      repo.store(nextVersion).futureValue

      repo.get(path).futureValue.value should equal(nextVersion)
      repo.versions(path).runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(
        appDef.version.toOffsetDateTime,
        nextVersion.version.toOffsetDateTime)
      repo.get(appDef.id, appDef.version.toOffsetDateTime).futureValue.value should be(appDef)
    }
    "be able to list multiple apps" in {
      val repo = newRepo
      val apps = Seq(AppDefinition(id = "app1".toRootPath), AppDefinition(id = "app2".toRootPath))
      Future.sequence(apps.map(repo.store)).futureValue
      repo.ids().runWith(Sink.seq).futureValue should contain theSameElementsAs
        Seq("/app1".toRootPath, "/app2".toRootPath)
    }
    "list the current versions of the apps" in {
      val repo = newRepo
      val appDef1 = AppDefinition("app1".toRootPath)
      val appDef2 = AppDefinition("app2".toRootPath)
      val appDef1Old = appDef1.copy(
        versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(appDef1.version.toDateTime.minusDays(1)))
      )
      val appDef2Old = appDef2.copy(
        versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(appDef2.version.toDateTime.minusDays(1)))
      )
      val allApps = Seq(appDef1, appDef2, appDef1Old, appDef2Old)

      repo.store(appDef1Old).futureValue
      repo.store(appDef2Old).futureValue
      repo.store(appDef1).futureValue
      repo.store(appDef2).futureValue

      repo.all().runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(appDef1, appDef2)
    }
    "should return all versions of a given app" in {
      val repo = newRepo
      val appDef1 = AppDefinition("app1".toRootPath)
      val version1 = appDef1.copy(
        versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(appDef1.version.toDateTime.minusDays(1)))
      )
      val version2 = appDef1.copy(
        versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(appDef1.version.toDateTime.minusDays(2)))
      )
      val version3 = appDef1.copy(
        versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(appDef1.version.toDateTime.minusDays(3)))
      )
      val appDef2 = AppDefinition("app2".toRootPath)
      val allApps = Seq(appDef1, version1, version2, version3, appDef2)
      allApps.foreach(repo.store(_).futureValue)
      repo.versions(appDef1.id).runWith(Sink.seq).futureValue should contain theSameElementsAs
        Seq(appDef1, version1, version2, version3).map(_.version.toOffsetDateTime)
    }
    "expunge should delete all versions of an app" in {
      val repo = newRepo
      val appDef1 = AppDefinition("app1".toRootPath)
      val version1 = appDef1.copy(
        versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(appDef1.version.toDateTime.minusDays(1)))
      )
      val version2 = appDef1.copy(
        versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(appDef1.version.toDateTime.minusDays(2)))
      )
      val version3 = appDef1.copy(
        versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(appDef1.version.toDateTime.minusDays(3)))
      )
      val appDef2 = AppDefinition("app2".toRootPath)
      val allApps = Seq(appDef1, version1, version2, version3, appDef2)
      allApps.foreach(repo.store(_).futureValue)

      repo.delete(appDef1.id).futureValue
      repo.versions(appDef1.id).runWith(Sink.seq).futureValue should be('empty)
      repo.get(appDef1.id).futureValue should be('empty)
      repo.all().runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(appDef2)
      repo.ids().runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(appDef2.id)
    }
  }
}

class AppEntityRepositoryTest extends AkkaUnitTest with AppRepositoryTest {
  private def newRepo = new AppEntityRepository(new MarathonStore[AppDefinition](
    new InMemoryStore,
    metrics,
    () => AppDefinition(),
    prefix = "app:"
  ), Some(25), metrics)

  "AppEntityRepository" should {
    behave like basicTest(newRepo)
  }
}

class AppZkRepositoryTest extends AkkaUnitTest with AppRepositoryTest with ZookeeperServerTest {
  private def defaultStore: ZkPersistenceStore = {
    val client = zkClient()
    val root = UUID.randomUUID().toString
    client.create(s"/$root").futureValue
    implicit val metrics = new Metrics(new MetricRegistry)
    new ZkPersistenceStore(client.usingNamespace(root), Duration.Inf)
  }
  private def newRepo = AppRepository.zkRepository(defaultStore, 25)

  "AppZkRepository" should {
    behave like basicTest(newRepo)
  }
}

class AppInMemRepositoryTest extends AkkaUnitTest with AppRepositoryTest {
  private def newRepo = AppRepository.inMemRepository(new InMemoryPersistenceStore, 25)

  "AppInMemRepositoryTest" should {
    behave like basicTest(newRepo)
  }
}