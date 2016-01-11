package mesosphere.marathon.state

import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.{ MarathonConf, MarathonSpec, StoreCommandFailedException }
import mesosphere.util.ThreadPoolContext
import mesosphere.util.state.memory.InMemoryStore
import mesosphere.util.state.{ PersistentEntity, PersistentStore }
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.rogach.scallop.ScallopConf
import org.scalatest.Matchers
import mesosphere.FutureTestSupport._

import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps

class MarathonStoreTest extends MarathonSpec with Matchers {
  var metrics: Metrics = _

  before {
    metrics = new Metrics(new MetricRegistry)
  }

  test("Fetch") {
    val state = mock[PersistentStore]
    val variable = mock[PersistentEntity]
    val now = Timestamp.now()
    val appDef = AppDefinition(id = "testApp".toPath, args = Some(Seq("arg")),
      versionInfo = AppDefinition.VersionInfo.forNewConfig(now))
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    when(variable.bytes).thenReturn(appDef.toProtoByteArray)
    when(state.load("app:testApp")).thenReturn(Future.successful(Some(variable)))
    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")
    val res = store.fetch("testApp")

    verify(state).load("app:testApp")
    assert(Some(appDef) == Await.result(res, 5.seconds), "Should return the expected AppDef")
  }

  test("FetchFail") {
    val state = mock[PersistentStore]

    when(state.load("app:testApp")).thenReturn(Future.failed(new StoreCommandFailedException("failed")))

    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()
    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")
    val res = store.fetch("testApp")

    verify(state).load("app:testApp")

    intercept[StoreCommandFailedException] {
      Await.result(res, 5.seconds)
    }
  }

  test("Modify") {
    val state = mock[PersistentStore]
    val variable = mock[PersistentEntity]
    val now = Timestamp.now()
    val appDef = AppDefinition(id = "testApp".toPath, args = Some(Seq("arg")),
      versionInfo = AppDefinition.VersionInfo.forNewConfig(now))

    val newAppDef = appDef.copy(id = "newTestApp".toPath)
    val newVariable = mock[PersistentEntity]
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    when(newVariable.bytes).thenReturn(newAppDef.toProtoByteArray)
    when(variable.bytes).thenReturn(appDef.toProtoByteArray)
    when(variable.withNewContent(any())).thenReturn(newVariable)
    when(state.load("app:testApp")).thenReturn(Future.successful(Some(variable)))
    when(state.update(newVariable)).thenReturn(Future.successful(newVariable))

    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")
    val res = store.modify("testApp") { _ =>
      newAppDef
    }

    assert(newAppDef == Await.result(res, 5.seconds), "Should return the new AppDef")
    verify(state).load("app:testApp")
    verify(state).update(newVariable)
  }

  test("ModifyFail") {
    val state = mock[PersistentStore]
    val variable = mock[PersistentEntity]
    val appDef = AppDefinition(id = "testApp".toPath, args = Some(Seq("arg")))

    val newAppDef = appDef.copy(id = "newTestApp".toPath)
    val newVariable = mock[PersistentEntity]
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    when(newVariable.bytes).thenReturn(newAppDef.toProtoByteArray)
    when(variable.bytes).thenReturn(appDef.toProtoByteArray)
    when(variable.withNewContent(any())).thenReturn(newVariable)
    when(state.load("app:testApp")).thenReturn(Future.successful(Some(variable)))
    when(state.update(newVariable)).thenReturn(Future.failed(new StoreCommandFailedException("failed")))

    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")
    val res = store.modify("testApp") { _ =>
      newAppDef
    }

    intercept[StoreCommandFailedException] {
      Await.result(res, 5.seconds)
    }
  }

  test("Expunge") {
    val state = mock[PersistentStore]
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    when(state.delete("app:testApp")).thenReturn(Future.successful(true))
    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")
    val res = store.expunge("testApp")

    Await.ready(res, 5.seconds)
    verify(state).delete("app:testApp")
  }

  test("ExpungeFail") {
    val state = mock[PersistentStore]
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    when(state.delete("app:testApp")).thenReturn(Future.failed(new StoreCommandFailedException("failed")))

    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")

    val res = store.expunge("testApp")

    intercept[StoreCommandFailedException] {
      Await.result(res, 5.seconds)
    }
  }

  test("Names") {
    val state = new InMemoryStore
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    def populate(key: String, value: Array[Byte]) = {
      state.load(key).futureValue match {
        case Some(ent) => state.update(ent.withNewContent(value)).futureValue
        case None      => state.create(key, value).futureValue
      }
    }

    populate("app:foo", Array())
    populate("app:bar", Array())
    populate("no_match", Array())

    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")
    val res = store.names()

    assert(Set("foo", "bar") == Await.result(res, 5.seconds).toSet, "Should return all application keys")
  }

  test("NamesFail") {
    val state = mock[PersistentStore]
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    when(state.allIds()).thenReturn(Future.failed(new StoreCommandFailedException("failed")))

    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")
    val res = store.names()

    whenReady(res.failed) { _ shouldBe a[StoreCommandFailedException] }
  }

  test("ConcurrentModifications") {
    val state = new InMemoryStore
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")

    store.store("foo", AppDefinition(id = "foo".toPath, instances = 0)).futureValue

    def plusOne() = {
      store.modify("foo") { f =>
        val appDef = f()
        appDef.copy(instances = appDef.instances + 1)
      }
    }

    val results = for (_ <- 0 until 1000) yield plusOne()

    implicit val ec = scala.concurrent.ExecutionContext.Implicits.global
    val res = Future.sequence(results)

    Await.ready(res, 5.seconds)

    assert(1000 == Await.result(store.fetch("foo"), 5.seconds).map(_.instances)
      .getOrElse(0), "Instances of 'foo' should be set to 1000")
  }

  // regression test for #1481
  ignore("names() correctly uses timeouts") {
    val state = new InMemoryStore() {

      override def allIds(): Future[scala.Seq[ID]] = Future {
        synchronized {
          blocking(wait())
        }
        Seq.empty
      }
    }
    val config = new ScallopConf(Seq("--master", "foo", "--marathon_store_timeout", "1")) with MarathonConf
    config.afterInit()

    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")

    noException should be thrownBy {
      Await.result(store.names(), 1.second)
    }
  }

  // regression test for #1507
  test("state.names() throwing exception is treated as empty iterator (ExecutionException without cause)") {
    val state = new InMemoryStore() {
      override def allIds(): Future[scala.Seq[ID]] = super.allIds()
    }
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")

    noException should be thrownBy {
      Await.result(store.names(), 1.second)
    }
  }

  class MyWeirdExecutionException extends ExecutionException("weird without cause")

  // regression test for #1507
  test("state.names() throwing exception is treated as empty iterator (ExecutionException with itself as cause)") {
    val state = new InMemoryStore() {
      override def allIds(): Future[scala.Seq[ID]] = super.allIds()
    }
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")

    noException should be thrownBy {
      Await.result(store.names(), 1.second)
    }
  }

  test("state.names() throwing exception is treated as empty iterator (direct)") {
    val state = new InMemoryStore() {
      override def allIds(): Future[scala.Seq[ID]] = super.allIds()
    }
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")

    noException should be thrownBy {
      Await.result(store.names(), 1.second)
    }
  }

  test("state.names() throwing exception is treated as empty iterator (RuntimeException in ExecutionException)") {
    val state = new InMemoryStore() {
      override def allIds(): Future[scala.Seq[ID]] = super.allIds()
    }
    val config = new ScallopConf(Seq("--master", "foo")) with MarathonConf
    config.afterInit()

    val store = new MarathonStore[AppDefinition](state, metrics, () => AppDefinition(), "app:")

    noException should be thrownBy {
      Await.result(store.names(), 1.second)
    }
  }

  def registry: MetricRegistry = new MetricRegistry
}
