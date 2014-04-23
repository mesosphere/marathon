package mesosphere.marathon.state

import org.junit.Test
import org.junit.Assert._
import mesosphere.marathon.api.v1.AppDefinition
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatest.junit.AssertionsForJUnit
import org.scalatest.mock.MockitoSugar
import org.apache.mesos.state.{Variable, State}
import java.util.concurrent.{Future => JFuture, ExecutionException}
import java.util.{ Iterator => JIterator }
import java.lang.{ Boolean => JBoolean }
import scala.concurrent.Await
import scala.concurrent.duration._
import mesosphere.marathon.StorageException
import scala.collection.JavaConverters._

class MarathonStoreTest extends AssertionsForJUnit with MockitoSugar {
  @Test
  def testFetch() {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val appDef = AppDefinition(id = "testApp")

    when(variable.value()).thenReturn(appDef.toProtoByteArray)
    when(future.get()).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())
    val res = store.fetch("testApp")

    verify(state).fetch("app:testApp")
    assertEquals("Should return the expected AppDef", Some(appDef), Await.result(res, 5 seconds))
  }

  @Test
  def testFetchFail() {
    val state = mock[State]
    val future = mock[JFuture[Variable]]

    when(future.get()).thenReturn(null)
    when(state.fetch("app:testApp")).thenReturn(future)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())
    val res = store.fetch("testApp")

    verify(state).fetch("app:testApp")

    intercept[StorageException] {
      Await.result(res, 5 seconds)
    }
  }

  @Test
  def testModify() {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val appDef = AppDefinition(id = "testApp")

    val newAppDef = appDef.copy(id = "newTestApp")
    val newVariable = mock[Variable]
    val newFuture = mock[JFuture[Variable]]

    when(newVariable.value()).thenReturn(newAppDef.toProtoByteArray)
    when(newFuture.get()).thenReturn(newVariable)
    when(variable.value()).thenReturn(appDef.toProtoByteArray)
    when(variable.mutate(any())).thenReturn(newVariable)
    when(future.get()).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(state.store(newVariable)).thenReturn(newFuture)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())
    val res = store.modify("testApp") { _ =>
      newAppDef
    }

    assertEquals("Should return the new AppDef", Some(newAppDef), Await.result(res, 5 seconds))
    verify(state).fetch("app:testApp")
    verify(state).store(newVariable)
  }

  @Test
  def testModifyFail() {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val appDef = AppDefinition(id = "testApp")

    val newAppDef = appDef.copy(id = "newTestApp")
    val newVariable = mock[Variable]
    val newFuture = mock[JFuture[Variable]]

    when(newVariable.value()).thenReturn(newAppDef.toProtoByteArray)
    when(newFuture.get()).thenReturn(null)
    when(variable.value()).thenReturn(appDef.toProtoByteArray)
    when(variable.mutate(any())).thenReturn(newVariable)
    when(future.get()).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(state.store(newVariable)).thenReturn(newFuture)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())
    val res = store.modify("testApp") { _ =>
      newAppDef
    }

    intercept[StorageException] {
      Await.result(res, 5 seconds)
    }
  }

  @Test
  def testExpunge() {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val resultFuture = mock[JFuture[JBoolean]]

    when(future.get()).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(resultFuture.get()).thenReturn(true)
    when(state.expunge(variable)).thenReturn(resultFuture)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())

    val res = store.expunge("testApp")

    assertTrue("Expunging existing variable should return true", Await.result(res, 5 seconds))
    verify(state).fetch("app:testApp")
    verify(state).expunge(variable)
  }

  @Test
  def testExpungeFail() {
    val state = mock[State]
    val future = mock[JFuture[Variable]]
    val variable = mock[Variable]
    val resultFuture = mock[JFuture[JBoolean]]

    when(future.get()).thenReturn(variable)
    when(state.fetch("app:testApp")).thenReturn(future)
    when(resultFuture.get()).thenReturn(null)
    when(state.expunge(variable)).thenReturn(resultFuture)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())

    val res = store.expunge("testApp")

    intercept[StorageException] {
      Await.result(res, 5 seconds)
    }
  }

  @Test
  def testNames() {
    val state = mock[State]
    val future = mock[JFuture[JIterator[String]]]

    when(future.get()).thenReturn(Seq("app:foo", "app:bar", "no_match").iterator.asJava)
    when(state.names()).thenReturn(future)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())
    val res = store.names()

    assertEquals("Should return all application keys", Seq("foo", "bar"), Await.result(res, 5 seconds).toSeq)
    verify(state).names()
  }

  @Test
  def testNamesFail() {
    val state = mock[State]
    val future = mock[JFuture[JIterator[String]]]

    when(future.get()).thenThrow(classOf[ExecutionException])
    when(state.names()).thenReturn(future)

    val store = new MarathonStore[AppDefinition](state, () => AppDefinition())
    val res = store.names()

    assertTrue("Should return empty iterator", Await.result(res, 5 seconds).isEmpty)
  }
}
