package mesosphere.marathon.state

import org.junit.Test
import org.junit.Assert._
import mesosphere.marathon.api.v1.AppDefinition
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import org.joda.time.DateTime

class AppRepositoryTest extends AssertionsForJUnit with MockitoSugar {
  @Test
  def testApp() {
    val store = mock[MarathonStore[AppDefinition]]
    val timestamp = new Timestamp(DateTime.now())
    val appDef = AppDefinition(id = "testApp", version = timestamp)
    val future = Future.successful(Some(appDef))

    when(store.fetch(s"testApp:${timestamp}")).thenReturn(future)

    val repo = new AppRepository(store)
    val res = repo.app("testApp", timestamp)

    assertEquals("Should return the correct AppDefinition", Some(appDef), Await.result(res, 5 seconds))
    verify(store).fetch(s"testApp:${timestamp}")
  }

  @Test
  def testStore() {
    val store = mock[MarathonStore[AppDefinition]]
    val appDef = AppDefinition(id = "testApp")
    val future = Future.successful(Some(appDef))
    val versionedKey = s"testApp:${appDef.version}"

    when(store.store(versionedKey, appDef)).thenReturn(future)
    when(store.store("testApp", appDef)).thenReturn(future)

    val repo = new AppRepository(store)
    val res = repo.store(appDef)

    assertEquals("Should return the correct AppDefinition", Some(appDef), Await.result(res, 5 seconds))
    verify(store).store(versionedKey, appDef)
    verify(store).store(s"testApp", appDef)
  }

  @Test
  def testAppIds() {
    val store = mock[MarathonStore[AppDefinition]]
    val future = Future.successful(Seq("app1", "app2", "app1:version", "app2:version").iterator)

    when(store.names()).thenReturn(future)

    val repo = new AppRepository(store)
    val res = repo.appIds()

    assertEquals("Should return only unversioned names", Seq("app1", "app2"), Await.result(res, 5 seconds))
    verify(store).names()
  }

  @Test
  def testApps() {
    val store = mock[MarathonStore[AppDefinition]]
    val appDef1 = AppDefinition("app1")
    val appDef2 = AppDefinition("app2")
    val appDef1Old = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(1)))
    val appDef2Old = appDef2.copy(version = Timestamp(appDef2.version.dateTime.minusDays(1)))
    val allApps = Seq(appDef1, appDef2, appDef1Old, appDef2Old)

    val future = Future.successful((Seq("app1", "app2") ++ allApps.map(x => s"${x.id}:${x.version}")).toIterator)

    when(store.names()).thenReturn(future)
    when(store.fetch(appDef1.id)).thenReturn(Future.successful(Some(appDef1)))
    when(store.fetch(appDef2.id)).thenReturn(Future.successful(Some(appDef2)))

    val repo = new AppRepository(store)
    val res = repo.apps()

    assertEquals("Should return only current versions", Seq(appDef1, appDef2), Await.result(res, 5 seconds))
    verify(store).names()
    verify(store).fetch(appDef1.id)
    verify(store).fetch(appDef2.id)
  }

  @Test
  def testListVersions() {
    val store = mock[MarathonStore[AppDefinition]]
    val appDef1 = AppDefinition("app1")
    val version1 = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(1)))
    val version2 = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(2)))
    val version3 = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(3)))
    val appDef2 = AppDefinition("app2")
    val allApps = Seq(appDef1, version1, version2, version3, appDef2)

    val future = Future.successful((Seq("app1", "app2") ++ allApps.map(x => s"${x.id}:${x.version}")).toIterator)

    when(store.names()).thenReturn(future)

    val repo = new AppRepository(store)
    val res = repo.listVersions(appDef1.id)

    val expected = Seq(appDef1.version, version1.version, version2.version, version3.version)
    assertEquals("Should return all versions of given app", expected, Await.result(res, 5 seconds))
    verify(store).names()
  }

  @Test
  def testExpunge() {
    val store = mock[MarathonStore[AppDefinition]]
    val appDef1 = AppDefinition("app1")
    val version1 = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(1)))
    val version2 = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(2)))
    val version3 = appDef1.copy(version = Timestamp(appDef1.version.dateTime.minusDays(3)))
    val appDef2 = AppDefinition("app2")
    val allApps = Seq(appDef1, version1, version2, version3, appDef2)

    val future = Future.successful((Seq("app1", "app2") ++ allApps.map(x => s"${x.id}:${x.version}")).toIterator)

    when(store.names()).thenReturn(future)
    when(store.expunge(any())).thenReturn(Future.successful(true))

    val repo = new AppRepository(store)
    val res = Await.result(repo.expunge(appDef1.id), 5 seconds).toSeq

    assertTrue("Should expunge all versions", res.size == 5)
    assertTrue("Should succeed", res.forall(identity))

    verify(store).names()
    verify(store).expunge("app1")
    for {
      app <- allApps
      if app.id == "app1"
    } verify(store).expunge(s"${app.id}:${app.version}")
  }
}
