package mesosphere.util.state

import mesosphere.marathon.StoreCommandFailedException
import mesosphere.marathon.integration.setup.IntegrationFunSuite
import mesosphere.FutureTestSupport._
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.{ BeforeAndAfter, Matchers }

/**
  * Common  tests for all persistent stores.
  */
trait PersistentStoreTest extends IntegrationFunSuite with Matchers with BeforeAndAfter {

  //this parameter is used for futureValue timeouts
  implicit val patienceConfig = PatienceConfig(Span(10, Seconds))

  test("Root node gets read"){
    val store = persistentStore
    store.allIds().futureValue should be(Seq.empty)
  }

  test("Fetch a non existing entity is possible") {
    val entity = fetch("notExistent")
    entity should be(None)
  }

  test("Create node is successful") {
    val entity = fetch("foo")
    entity should be('empty)
    val stored = persistentStore.create("foo", "Hello".getBytes).futureValue
    val read = fetch("foo")
    read should be('defined)
    read.get.bytes should be("Hello".getBytes)
  }

  test("Multiple creates should create only the first time") {
    val entity = fetch("foo2")
    entity should be('empty)
    persistentStore.create("foo", "Hello".getBytes).futureValue.bytes should be("Hello".getBytes)
    whenReady(persistentStore.create("foo", "Hello again".getBytes).failed) { _ shouldBe a[StoreCommandFailedException] }
  }

  test("Update node is successful") {
    val entity = fetch("foo")
    entity should be('empty)
    val stored = persistentStore.create("foo", "Hello".getBytes).futureValue
    val read = fetch("foo")
    read should be('defined)
    read.get.bytes should be("Hello".getBytes)
    val update = store(read.get.withNewContent("Hello again".getBytes))
    val readAgain = fetch("foo")
    readAgain should be('defined)
    readAgain.get.bytes should be("Hello again".getBytes)
  }

  test("Multiple updates will update only with correct version") {
    val entity = fetch("foo")
    entity should be('empty)
    val stored = persistentStore.create("foo", "Hello".getBytes).futureValue
    val read = fetch("foo")
    val read2 = fetch("foo")
    read should be('defined)
    read2 should be('defined)
    read.get.bytes should be("Hello".getBytes)
    read2.get.bytes should be("Hello".getBytes)
    persistentStore.update(read.get.withNewContent("Hello again".getBytes)).futureValue.bytes should be("Hello again".getBytes)
    whenReady(persistentStore.update(read2.get.withNewContent("Will be None".getBytes)).failed) { _ shouldBe a[StoreCommandFailedException] }
    val readAgain = fetch("foo")
    readAgain.get.bytes should be("Hello again".getBytes)
  }

  test("Expunge on a non existing entry will fail") {
    persistentStore.delete("notExistent").futureValue should be(false)
  }

  test("Expunge will delete an entry") {
    val entity = fetch("foo")
    entity should be('empty)
    val stored = persistentStore.create("foo", "Hello".getBytes).futureValue
    val read = fetch("foo")
    read should be('defined)
    read.get.bytes should be("Hello".getBytes)
    val result = persistentStore.delete("foo")
    result.futureValue should be(true)
    fetch("foo") should be('empty)
  }

  test("All ids in namespace can be listed") {
    persistentStore.allIds().futureValue should be ('empty)
    persistentStore.create("foo", "Hello".getBytes).futureValue
    persistentStore.allIds().futureValue should be (Seq("foo"))
  }

  before {
    persistentStore match {
      case manager: PersistentStoreManagement => manager.initialize().futureValue
      case _                                  => //ignore
    }
    persistentStore.allIds().futureValue.foreach { entry =>
      persistentStore.delete(entry).futureValue
    }
  }

  def persistentStore: PersistentStore
  def store(entity: PersistentEntity): PersistentEntity = persistentStore.update(entity).futureValue
  def fetch(key: String): Option[PersistentEntity] = persistentStore.load(key).futureValue
}
