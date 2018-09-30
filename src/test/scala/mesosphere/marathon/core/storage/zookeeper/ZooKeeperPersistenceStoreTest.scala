package mesosphere.marathon
package core.storage.zookeeper

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest
import mesosphere.marathon.core.storage.zookeeper.PersistenceStore._
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.util.ZookeeperServerTest
import org.apache.curator.framework.CuratorFramework
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Random, Success}

class ZooKeeperPersistenceStoreTest extends UnitTest
  with ZookeeperServerTest
  with StrictLogging {

  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  lazy val client: CuratorFramework = zkClient(namespace = Some("test")).client
  lazy val factory: AsyncCuratorBuilderFactory = AsyncCuratorBuilderFactory(client)
  lazy val metrics: Metrics = DummyMetrics
  lazy val store: ZooKeeperPersistenceStore = new ZooKeeperPersistenceStore(metrics, factory, parallelism = 1)

  def randomPath(prefix: String = "", size: Int = 10): String =
    s"$prefix/${Random.alphanumeric.take(size).mkString}"

  def randomNodes(size: Int, payload: String = "foo", prefix: String = ""): Seq[Node] =
    (1 to size).map(i => Node(randomPath(prefix), ByteString(payload)))

  "SimplePersistenceStore" when {

    "create" should {
      "create a single node" in {
        Given("a single node")
        val path = randomPath()
        val res = store.create(Node(path, ByteString("foo"))).futureValue

        Then("a path of the created node is returned")
        res shouldBe path
      }

      "creating multiple nodes" in {
        When("multiple nodes are created")
        val nodes = randomNodes(3)
        val res = store.create(nodes).runWith(Sink.seq).futureValue

        Then("a stream with created paths is returned")
        res.size shouldBe 3
        res should contain theSameElementsAs (nodes.map(_.path))
      }

      "creating an existing node leads to an error" in {
        When("multiple nodes are created")
        val path = randomPath()
        store.create(Node(path, ByteString("foo"))).futureValue

        Then("trying to create a node with the same path leads to an exception")
        intercept[NodeExistsException] {
          Await.result(store.create(Node(path, ByteString("foo"))), patienceConfig.timeout)
        }
      }

      "creating multiple nodes with duplication" in {
        When("multiple nodes are created with a duplicate node at the end")
        val nodes = randomNodes(3)

        Then("an exception is thrown")
        val created = new TrieMap[String, Done]()

        intercept[NodeExistsException] {
          Await.result(store
            .create(nodes :+ nodes.head)
            .runWith(Sink.foreach(p => created.put(p, Done))), patienceConfig.timeout)
        }

        And("but first 3 nodes were successfully created")
        created.size shouldBe 3
        created.keys should contain theSameElementsAs (nodes.map(_.path))
      }
    }

    "read" should {
      "read a single node" in {
        Given("a single node")
        val path = randomPath()
        store.create(Node(path, ByteString("foo"))).futureValue

        And("node's data is read")
        val Success(node) = store.read(path).futureValue

        Then("read data is correct")
        node.data.utf8String shouldBe "foo"
      }

      "reading a non-existing node returns a failure" in {
        When("a non-existing node is read")
        val path = randomPath()

        Then("a failure is returned")

        val Failure(e) = store.read(path).futureValue
        assert(e.isInstanceOf[NoNodeException])
        e.asInstanceOf[NoNodeException].getPath shouldBe path
      }

      "read multiple nodes" in {
        When("multiple nodes are created")
        val nodes = randomNodes(3, "foo")
        store.create(nodes).runWith(Sink.ignore).futureValue

        And("node data is read")
        val res = store.read(nodes.map(_.path)).runWith(Sink.seq).futureValue

        Then("read operations should be successful")
        res.size shouldBe 3
        res.collect{ case Success(node) => node.data.utf8String } should contain theSameElementsAs (Seq("foo", "foo", "foo"))
      }
    }

    "update" should {
      "update a single node" in {
        Given("a single node")
        val path = randomPath()
        store.create(Node(path, ByteString("foo"))).futureValue

        And("the node is updated")
        store.update(Node(path, ByteString("bar"))).futureValue

        Then("node data is updated")
        val Success(node) = store.read(path).futureValue
        node.data.utf8String shouldBe "bar"
      }

      "update a non-existing node" in {
        When("a non-existing node is updated")
        val path = randomPath()

        Then("an exception is thrown")
        intercept[NoNodeException] {
          Await.result(store.update(Node(path, ByteString("bar"))), patienceConfig.timeout)
        }
      }

      "update multiple nodes" in {
        When("multiple nodes are created")
        val nodes = randomNodes(3)
        store.create(nodes).runWith(Sink.seq).futureValue

        And("node data is updated")
        store.update(nodes.map(el => Node(el.path, ByteString("bar")))).runWith(Sink.seq).futureValue

        Then("update operation is successful")
        val res = store.read(nodes.map(_.path)).runWith(Sink.seq).futureValue
        res.size shouldBe 3
        res.collect{ case Success(node) => node.data.utf8String } should contain theSameElementsAs (Seq("bar", "bar", "bar"))
      }
    }

    "delete" should {
      "delete a single node" in {
        Given("a single node")
        val path = randomPath()
        store.create(Node(path, ByteString("foo"))).futureValue

        Then("it can be successfully deleted")
        store.delete(path).futureValue shouldBe path
      }

      "delete a non-existing node does not lead to an exception" in {
        When("trying to delete a non-existing node")
        val path = randomPath()

        Then("it does NOT lead to an exception")
        store.delete(path).futureValue shouldBe path
      }

      "delete multiple nodes" in {
        When("multiple nodes are created")
        val nodes = randomNodes(3)
        store.create(nodes).runWith(Sink.seq).futureValue

        Then("they can be successfully deleted")
        val res = store.delete(nodes.map(_.path)).runWith(Sink.seq).futureValue
        res.size shouldBe 3
        res should contain theSameElementsAs (nodes.map(_.path))
      }
    }

    "exists" should {
      "is successful for existing nodes" in {
        Given("a single node")
        val path = randomPath()
        store.create(Node(path, ByteString("foo"))).futureValue

        Then("exists operation is successful")
        store.exists(path).futureValue shouldBe true
      }

      "not successful for non-existing nodes" in {
        val path = randomPath()
        And("exists operation for a non-existing node is unsuccessful")
        store.exists(path + "nope").futureValue shouldBe false
      }

      "check existence of multipe nodes" in {
        When("multiple nodes are created")
        val nodes = randomNodes(3, "foo")
        store.create(nodes).runWith(Sink.ignore).futureValue

        And("node existence is successfully checked")
        val res = store.exists(nodes.map(_.path)).runWith(Sink.seq).futureValue
        res.size shouldBe 3
        res should contain theSameElementsAs Seq(true, true, true)
      }
    }

    "children" should {
      "fetching node children" in {
        When("multiple nested nodes are created")
        val nodes = randomNodes(size = 3, prefix = "/home")
        store.create(nodes).runWith(Sink.seq).futureValue

        And("children are fetched")
        val children = store.children("/home", true).futureValue

        children should contain theSameElementsAs (nodes.map(_.path))
      }

      "fetching children of an non-existing node leads to an exception" in {
        And("children for a non-existing node are fetched")
        intercept[NoNodeException] {
          Await.result(store.children(randomPath(), true), patienceConfig.timeout)
        }
      }
    }

    "sync" should {
      "sync successfully" in {
        When("state is synced")
        val done = store.sync("/").futureValue

        Then("operation should be successful")
        done shouldBe Done
      }
    }

    "transaction" should {
      "complete successfully for two create operations" in {
        val prefix = randomPath()
        When("transaction parent node is created")
        store.create(Node(prefix, ByteString.empty)).futureValue

        And("a transaction with two create operations is submitted")
        val ops = Seq(
          CreateOp(Node(randomPath(prefix), ByteString("foo"))),
          CreateOp(Node(randomPath(prefix), ByteString("foo"))))

        Then("a transaction should be successful")
        val res = store.transaction(ops).futureValue
        res shouldBe Done

        And(s"the should be two children nodes under $prefix")
        val children = store.children(prefix, true).futureValue
        children.size shouldBe 2
        children should contain theSameElementsAs (ops.map(_.node.path))
      }

      "complete successfully for a create and update operations" in {
        val prefix = randomPath()
        When("transaction parent node is created")
        store.create(Node(prefix, ByteString.empty)).futureValue

        And("a transaction is submitted")
        val path = randomPath(prefix)
        val ops = Seq(
          CreateOp(Node(path, ByteString("foo"))),
          UpdateOp(Node(path, ByteString("bar"))))

        Then("a transaction should be successful")
        val res = store.transaction(ops).futureValue
        res shouldBe Done

        And(s"the should be one node under $prefix")
        val children = store.children(prefix, true).futureValue
        children.size shouldBe 1
        children.head shouldBe path

        And("child node has the updated content")
        val Success(node) = store.read(path).futureValue
        node.data.utf8String shouldBe "bar"
      }

      "complete successfully for a create and delete operations" in {
        val prefix = randomPath()
        When("transaction parent node is created")
        store.create(Node(prefix, ByteString.empty)).futureValue

        And("a transaction with create, update and delete operations is submitted")
        val path = randomPath(prefix)
        val ops = Seq(
          CreateOp(Node(path, ByteString("foo"))),
          DeleteOp(path))

        Then("a transaction should be successful")
        val res = store.transaction(ops).futureValue
        res shouldBe Done

        And(s"no children nodes are found under $prefix")
        val children = store.children(prefix, true).futureValue
        children.size shouldBe 0
      }

      "fail if one of the operations returns an error" in {
        val prefix = randomPath()
        When("transaction parent node is created")
        store.create(Node(prefix, ByteString.empty)).futureValue

        And("a transaction with create and erroneous update operations is submitted")
        val createOp = CreateOp(Node(randomPath(prefix), ByteString("foo")))
        val updateOp = UpdateOp(Node(randomPath(prefix), ByteString("bar")))

        Then("an exception is thrown")
        intercept[NoNodeException] {
          Await.result(store.transaction(Seq(createOp, updateOp)), patienceConfig.timeout)
        }

        And(s"no children nodes are found under $prefix")
        val children = store.children(prefix, true).futureValue
        children.size shouldBe 0
      }

      "succeed for a create and check operations" in {
        val prefix = randomPath()
        When("transaction parent node is created")
        store.create(Node(prefix, ByteString.empty)).futureValue

        And("a transaction is submitted")
        val path = randomPath(prefix)
        val ops = Seq(
          CreateOp(Node(path, ByteString("foo"))),
          CheckOp(path))

        Then("transaction should be successful")
        val res = store.transaction(ops).futureValue
        res shouldBe Done

        And(s"the should be one node under $prefix")
        val children = store.children(prefix, true).futureValue
        children.size shouldBe 1
      }

      "fail if check operation returns an error" in {
        val prefix = randomPath()
        When("transaction parent node is created")
        store.create(Node(prefix, ByteString.empty)).futureValue

        And("a transaction with create and erroneous check operations is submitted")
        val path = randomPath(prefix)
        val ops = Seq(
          CheckOp(path),
          CreateOp(Node(path, ByteString("foo"))))

        Then("an exception is thrown")
        intercept[NoNodeException] {
          Await.result(store.transaction(ops), patienceConfig.timeout)
        }

        And(s"no children nodes are found under $prefix")
        val children = store.children(prefix, true).futureValue
        children.size shouldBe 0
      }
    }
  }
}
