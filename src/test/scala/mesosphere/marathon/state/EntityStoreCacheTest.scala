package mesosphere.marathon.state

import mesosphere.marathon.Protos.MarathonApp
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.{ MarathonSpec, Protos }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfter, GivenWhenThen, Matchers }

import scala.collection.mutable
import scala.concurrent.Future

class EntityStoreCacheTest extends MarathonSpec with GivenWhenThen with Matchers with BeforeAndAfter with ScalaFutures with Mockito {

  test("The onElected trigger fills the cache") {
    Given("A store with some entries")
    val names = Set("a", "b", "c")
    content ++= names.map(t => t -> new TestApp(t))

    When("On elected is called on the cache")
    entityCache.onElected.futureValue

    Then("All values are cached")
    entityCache.cache should have size 3
    entityCache.cache.keySet should be(names)
  }

  test("The onDefeated trigger will clear the state") {
    Given("A pre-filled entityCache")
    val names = Set("a", "b", "c")
    entityCache.cache ++= names.map(t => t -> Some(new TestApp(t)))

    When("On defeated is called on the cache")
    entityCache.onDefeated

    Then("All values are removed")
    entityCache.cache should have size 0
  }

  test("Fetching an entry will succeed without querying store") {
    Given("A pre-filled entityCache")
    val store = mock[EntityStore[TestApp]]
    entityCache = new EntityStoreCache[TestApp](store)
    val names = Set("a", "b", "c")
    entityCache.cache ++= names.map(t => t -> Some(new TestApp(t)))

    When("Fetch an existing entry from the cache")
    val a = entityCache.fetch("a").futureValue

    Then("The value is returned without interaction to the underlying store")
    a should be(Some(TestApp("a")))
    verify(store, never).fetch(any)

    And("Fetch an existing entry with version from the cache")
    val aWithVersion = entityCache.fetch("a:1970-01-01T00:00:00.000Z").futureValue

    Then("The value is returned without interaction to the underlying store")
    aWithVersion should be(Some(TestApp("a")))
    verify(store, never).fetch(any)

    And("Fetch a non existing entry from the cache")
    val notExisting = entityCache.fetch("notExisting").futureValue

    Then("No value is returned without interaction to the underlying store")
    notExisting should be(None)
    verify(store, never).fetch(any)
  }

  test("Fetching a versioned entry will delegate to the store") {
    Given("A pre-filled entityCache")
    val names = Set("a", "b", "c")
    val fixed = Timestamp.now()
    content ++= names.map(t => s"$t:$fixed" -> TestApp(t, fixed))

    When("A known versioned entry is fetched")
    val a = entityCache.fetch(s"a:$fixed").futureValue

    Then("The versioned entry is read from cache")
    a should be (Some(TestApp("a", fixed)))

    And("An unknown versioned entry is fetched")
    val unknown = entityCache.fetch(s"a:${Timestamp.zero}").futureValue

    Then("The store is used to fetch that version")
    unknown should be (None)
  }

  test("Modify will update the cache") {
    Given("A pre-filled entityCache")
    val names = Set("a", "b", "c")
    val update = TestApp("a", Timestamp.now())
    content ++= names.map(t => t -> TestApp(t))
    entityCache.onElected.futureValue

    When("Modify an entity")
    val a = entityCache.modify("a")(_ => update).futureValue

    Then("The value is returned and the cache is updated")
    a should be(update)
    entityCache.cache("a") should be(Some(update))
  }

  test("Expunge will also clear the cache") {
    Given("A pre-filled entityCache")
    val names = Set("a", "b", "c")
    val update = TestApp("a", Timestamp.now())
    content ++= names.map(t => t -> TestApp(t))
    entityCache.onElected.futureValue

    When("Expunge an entity")
    val result = entityCache.expunge("a").futureValue

    Then("The value is expunged")
    result should be(true)
    entityCache.cache.get("a") should be(None)
  }

  test("Names will list all entries (versioned and unversioned)") {
    Given("A pre-filled entityCache")
    val names = Set("a", "b", "c")
    val now = Timestamp.now()
    content ++= (names.map(t => t -> TestApp(t)) ++ names.map(t => s"$t:$now" -> TestApp(t, now)))
    content should have size 6
    entityCache.onElected.futureValue

    When("Get all names in the cache")
    val allNames = entityCache.names().futureValue

    Then("All names are returned")
    allNames should have size 6
    entityCache.cache should have size 6
    entityCache.cache.values.flatten should have size 3
  }

  var content: mutable.Map[String, TestApp] = _
  var store: EntityStore[TestApp] = _
  var entityCache: EntityStoreCache[TestApp] = _

  before {
    content = mutable.Map.empty
    store = new TestStore[TestApp](content)
    entityCache = new EntityStoreCache(store)
  }

  case class TestApp(id: String, version: Timestamp = Timestamp.zero) extends MarathonState[Protos.MarathonApp, TestApp] {
    override def mergeFromProto(message: MarathonApp): TestApp = ???
    override def mergeFromProto(bytes: Array[Byte]): TestApp = ???
    override def toProto: MarathonApp = ???
  }
  class TestStore[T](map: mutable.Map[String, T]) extends EntityStore[T] {
    override def fetch(key: String): Future[Option[T]] = Future.successful(map.get(key))
    override def modify(key: String, onSuccess: (T) => Unit)(update: Update): Future[T] = {
      val updated = update(() => map(key))
      onSuccess(updated)
      Future.successful(updated)
    }
    override def names(): Future[Seq[String]] = Future.successful(map.keys.toSeq)
    override def expunge(key: String, onSuccess: () => Unit): Future[Boolean] = {
      onSuccess()
      Future.successful(true)
    }
  }
}
