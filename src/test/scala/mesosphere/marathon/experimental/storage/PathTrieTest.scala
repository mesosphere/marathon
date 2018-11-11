package mesosphere.marathon
package experimental.storage

import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest

import collection.JavaConverters._

class PathTrieTest extends UnitTest {
  import PathTrieTest._

  val paths = List(
    "/eng/dev/foo",
    "/eng/dev/bar",
    "/eng/dev/qa/service1",
    "/eng/dev/qa/soak/database/mysql",
    "/sales/demo/twitter",
    "/eng/ui/tests",
    "/eng/ui/jenkins/jobs/master"
  )

  val pathsWithData: Map[String, Array[Byte]] = paths
    .zip((1 to paths.length)
      .map(_ => "bloob".getBytes))
    .toMap

  "PathTrie" should {
    "add nodes to an empty tree" in {
      val trie = new PathTrie()
      When("adding new nodes")
      paths.foreach(trie.addPath(_))

      prettyPrint(trie.getRoot)

      Then("there are nodes added")
      trie.getRoot.children.size shouldBe 2
    }

    "add nodes to an existing path" in {
      val trie = new PathTrie()
      Given("a trie with some nodes")
      paths.foreach(trie.addPath(_))

      When("new path is added to an existing one")
      trie.addPath("/eng/ui/builds")

      Then("all children should exist")
      trie.getChildren("/eng/ui", false) should contain theSameElementsAs (Seq("tests", "jenkins", "builds"))
    }

    "not override existing nodes" in {
      val trie = new PathTrie()
      Given("a trie with some nodes")
      paths.foreach(trie.addPath(_))

      When("adding existing nodes")
      trie.addPath("/eng/ui")

      Then("existing path in *not* overridden")
      trie.getChildren("/eng/ui", false) should contain theSameElementsAs (Set("tests", "jenkins"))
    }

    "read existing paths" in {
      val trie = new PathTrie()
      Given("a trie with some nodes")
      paths.foreach(trie.addPath(_))

      prettyPrint(trie.getRoot)

      Then("tree structure should be properly initialized")
      trie.getChildren("/", false) should contain theSameElementsAs (Set("eng", "sales"))
      trie.getChildren("/sales", true) should contain theSameElementsAs (Set("/sales/demo"))
      trie.getChildren("/eng/ui", false) should contain theSameElementsAs (Set("tests", "jenkins"))
    }

    "delete existing nodes" in {
      val trie = new PathTrie()
      Given("a trie with some nodes")
      paths.foreach(trie.addPath(_))

      When("removing a path")
      trie.deletePath("/eng/ui")

      Then("the trie should not contain removed path")
      prettyPrint(trie.getRoot)
      trie.getChildren("/eng/ui", false) shouldBe null
    }

    "find leaf nodes" in {
      val trie = new PathTrie()
      Given("a trie with some nodes")
      paths.foreach(trie.addPath(_))

      When("fetching leaf nodes")
      trie.getLeafs("/").asScala should contain theSameElementsAs (paths)
      trie.getLeafs("/sales").asScala should contain theSameElementsAs List("/sales/demo/twitter")
      trie.getLeafs("/eng/ui").asScala should contain theSameElementsAs List("/eng/ui/tests", "/eng/ui/jenkins/jobs/master")
    }

    "add nodes with data" in {
      val trie = new PathTrie()
      Given("a trie with some nodes")
      pathsWithData.foreach{ case (p, d) => trie.addPath(p, d); }

      prettyPrint(trie)

      trie.getNodeData("/eng") shouldBe null
      trie.getNodeData("/eng/dev") shouldBe null
      paths.foreach(p => trie.getNodeData(p) shouldBe "bloob".getBytes)
    }

    "update nodes with data" in {
      val trie = new PathTrie()
      Given("a trie with empty nodes")
      paths.foreach(trie.addPath(_))

      When ("set existing path with data")
      trie.addPath(paths.head, "bloob".getBytes)

      prettyPrint(trie)

      Then("data should be there")
      trie.getNodeData(paths.head) shouldBe "bloob".getBytes

      And("update node with new data")
      trie.addPath(paths.head, "bluuub".getBytes)

      Then("updated data should be there")
      trie.getNodeData(paths.head) shouldBe "bluuub".getBytes
    }

    "check node existence" in {
      val trie = new PathTrie()
      Given("a trie with empty nodes")
      paths.foreach(trie.addPath(_))

      Then("nodes should exist")
      paths.foreach(trie.existsNode(_) shouldBe true)

      And("non-existing nodes should not")
      trie.existsNode("/foo/bar/bazz") shouldBe false
    }
  }
}

object PathTrieTest extends StrictLogging {
  def prettyPrint(trie: PathTrie): Unit = prettyPrint(trie.getRoot)

  def prettyPrint(node: PathTrie.TrieNode, indent: String = " "): Unit = {
    val children = node.children.asScala.toList.sortBy(_._1)

    children.foreach {
      case (name, node) =>
        val dataString = if (node.data == null) "" else ByteString(node.data).take(25).utf8String.replace("\n", "")
        logger.info(s"$indent|_$name [$dataString]")
        prettyPrint(node, indent.padTo(indent.length + 3, ' '))
    }
  }
}
