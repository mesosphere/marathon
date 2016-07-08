package mesosphere.marathon.core.storage

import akka.Done
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.Sink
import mesosphere.AkkaUnitTest
import mesosphere.marathon.StoreCommandFailedException

import scala.util.{ Failure, Success }

private[storage] case class TestClass1(str: String, int: Int)

private[storage] trait PersistenceStoreTest { this: AkkaUnitTest =>
  val rootId: String
  def createId: String
  def singleTypeStore[K, Serialized](store: => PersistenceStore[K, Serialized])(
    implicit
    ir: IdResolver[String, K, TestClass1, Serialized],
    m: Marshaller[TestClass1, Serialized],
    um: Unmarshaller[Serialized, TestClass1]): Unit = {

    "list nothing at the root" in {
      store.ids(rootId).runWith(Sink.seq).futureValue should equal(Nil)
    }
    "list nothing at a random folder" in {
      store.ids(createId).runWith(Sink.seq).futureValue should equal(Nil)
    }
    "keys lists all keys regardless of the nesting layer" in {
      val tc = TestClass1("abc", 4)
      store.create("list/1", tc).futureValue should be(Done)
      store.create("list/1/2", tc).futureValue should be(Done)
      store.create("list2/1/2/3", tc).futureValue should be(Done)
      val all = store.keys().runWith(Sink.seq).futureValue.map(ir.fromStorageId)
      all should contain theSameElementsAs Seq("list", "list/1", "list/1/2", "list2",
        "list2/1", "list2/1/2", "list2/1/2/3")
    }
    "create and then read an object" in {
      val tc = TestClass1("abc", 1)
      store.create("task-1", tc).futureValue should be(Done)
      store.get("task-1").futureValue.value should equal(tc)
    }
    "create then list an object" in {
      val tc = TestClass1("abc", 2)
      store.create("task-2", tc).futureValue should be(Done)
      store.ids(rootId).runWith(Sink.seq).futureValue should contain("task-2")
    }
    "not allow an object to be created if it already exists" in {
      val tc = TestClass1("abc", 3)
      store.create("task-3", tc).futureValue should be(Done)
      store.create("task-3", tc).failed.futureValue shouldBe a[StoreCommandFailedException]
    }
    "create an object at a nested path" in {
      val tc = TestClass1("abc", 3)
      store.create("nested/object", tc).futureValue should be(Done)
      store.get("nested/object").futureValue.value should equal(tc)
      store.ids(rootId).runWith(Sink.seq).futureValue should contain("nested")
      store.ids("nested").runWith(Sink.seq).futureValue should contain theSameElementsAs Seq("object")
    }
    "create two objects at a nested path" in {
      val tc1 = TestClass1("a", 1)
      val tc2 = TestClass1("b", 2)
      store.create("nested-2/1", tc1).futureValue should be(Done)
      store.create("nested-2/2", tc2).futureValue should be(Done)
      store.ids(rootId).runWith(Sink.seq).futureValue should contain("nested-2")
      store.ids("nested-2").runWith(Sink.seq).futureValue should contain theSameElementsAs Seq("1", "2")
      store.get("nested-2/1").futureValue.value should be(tc1)
      store.get("nested-2/2").futureValue.value should be(tc2)
    }
    "delete idempotently" in {
      store.create("delete-me", TestClass1("def", 3)).futureValue should be(Done)
      store.delete("delete-me").futureValue should be(Done)
      store.delete("delete-me").futureValue should be(Done)
      store.ids(rootId).runWith(Sink.seq).futureValue should not contain ("delete-me")
    }
    "delete at nested paths" in {
      store.create("nested-delete/1", TestClass1("def", 4)).futureValue should be(Done)
      store.create("nested-delete/2", TestClass1("ghi", 5)).futureValue should be(Done)
      store.delete("nested-delete/1").futureValue should be(Done)
      store.ids("nested-delete").runWith(Sink.seq).futureValue should contain theSameElementsAs Seq("2")
    }
    "update an object" in {
      val created = TestClass1("abc", 2)
      val updated = TestClass1("def", 3)
      store.create("update/1", created).futureValue should be(Done)
      var calledWithTc = Option.empty[TestClass1]
      store.update("update/1") { old: TestClass1 =>
        calledWithTc = Option(old)
        Success(updated)
      }.futureValue should equal(created)
      calledWithTc.value should equal(created)
      store.get("update/1").futureValue.value should equal(updated)
    }
    "not update an object that doesn't exist" in {
      store.update("update/2") { old: TestClass1 =>
        Success(TestClass1("abc", 3))
      }.failed.futureValue shouldBe a[StoreCommandFailedException]
    }
    "not update an object if the callback returns a failure" in {
      val tc = TestClass1("abc", 3)
      store.create("update/3", tc).futureValue should be(Done)
      store.update("update/3") { _: TestClass1 =>
        Failure[TestClass1](new NotImplementedError)
      }.futureValue should equal(tc)
      store.get("update/3").futureValue.value should equal(tc)
    }
  }
}