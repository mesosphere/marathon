package mesosphere.marathon.state

import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.repository.AppRepository
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.util.state.memory.InMemoryStore

import scala.collection.immutable.Seq
import scala.concurrent.Future

trait AppRepositoryTest { this: AkkaUnitTest =>
  var metrics: Metrics = new Metrics(new MetricRegistry)

  def basicTest(newRepo: => AppRepository): Unit = {
    "be able to store and retrieve an app" in {
      val repo = newRepo

      val path = "testApp".toRootPath
      val timestamp = Timestamp.now()
      val appDef = AppDefinition(id = path, versionInfo = AppDefinition.VersionInfo.forNewConfig(timestamp))

      repo.store(appDef).futureValue
      repo.currentVersion(appDef.id).futureValue.value should equal(appDef)
    }
    "be able to update an app and keep the old version" in {
      val repo = newRepo
      val path = "testApp".toRootPath
      val appDef = AppDefinition(id = path)
      val nextVersion = appDef.copy(versionInfo = AppDefinition.VersionInfo.forNewConfig(Timestamp(2)))

      repo.store(appDef).futureValue
      repo.store(nextVersion).futureValue

      repo.currentVersion(path).futureValue.value should equal(nextVersion)
      repo.listVersions(path).runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(
        appDef.version.toOffsetDateTime,
        nextVersion.version.toOffsetDateTime)
    }
    "be able to list multiple apps" in {
      val repo = newRepo
      val apps = Seq(AppDefinition(id = "app1".toRootPath), AppDefinition(id = "app2".toRootPath))
      Future.sequence(apps.map(repo.store)).futureValue
      repo.allPathIds().runWith(Sink.seq).futureValue should contain theSameElementsAs
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

      repo.apps().runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(appDef1, appDef2)
    }
  }

  /*


  test("Apps") {
    val store = mock[MarathonStore[AppDefinition]]


    val future = Future.successful(Seq("app1", "app2") ++ allApps.map(x => s"${x.id}:${x.version}"))

    when(store.names()).thenReturn(future)
    when(store.fetch(appDef1.id.toString)).thenReturn(Future.successful(Some(appDef1)))
    when(store.fetch(appDef2.id.toString)).thenReturn(Future.successful(Some(appDef2)))

    val repo = new AppEntityRepository(store, None, metrics)
    val res = repo.apps().runWith(Sink.seq)

    assert(Seq(appDef1, appDef2) == Await.result(res, 5.seconds), "Should return only current versions")
    verify(store).names()
    verify(store).fetch(appDef1.id.toString)
    verify(store).fetch(appDef2.id.toString)
  }

  test("ListVersions") {
    val store = mock[MarathonStore[AppDefinition]]
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

    val future = Future.successful(Seq("app1", "app2") ++ allApps.map(x => s"${x.id.safePath}:${x.version}"))

    when(store.names()).thenReturn(future)

    val repo = new AppEntityRepository(store, None, metrics)
    val res = repo.listVersions(appDef1.id).map(Timestamp(_)).runWith(Sink.seq)

    val expected = Seq(appDef1.version, version1.version, version2.version, version3.version)
    assert(expected == Await.result(res, 5.seconds), "Should return all versions of given app")
    verify(store).names()
  }

  test("Expunge") {
    val store = mock[MarathonStore[AppDefinition]]
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

    val future = Future.successful(Seq("app1", "app2") ++ allApps.map(x => s"${x.id.safePath}:${x.version}"))

    when(store.names()).thenReturn(future)
    when(store.expunge(any(), any())).thenReturn(Future.successful(true))

    val repo = new AppEntityRepository(store, None, metrics)
    Await.result(repo.expunge(appDef1.id), 5.seconds)

    verify(store).names()
    verify(store).expunge("app1", null) //the null is due to mockito and default arguments in scala
    for {
      app <- allApps
      if app.id.toString == "app1"
    } verify(store).expunge(s"${app.id}:${app.version}")
  }
  */
}

class AppEntityRepositoryTest extends AkkaUnitTest with AppRepositoryTest {
  def newRepo = new AppEntityRepository(new MarathonStore[AppDefinition](
    new InMemoryStore,
    metrics,
    () => AppDefinition(),
    prefix = "app:"
  ), Some(25), metrics)

  "AppEntityRepository" should {
    behave like basicTest(newRepo)
  }
}
