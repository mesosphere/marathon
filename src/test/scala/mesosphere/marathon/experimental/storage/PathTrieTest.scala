package mesosphere.marathon
package experimental.storage

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
    }
  }
}

object PathTrieTest extends StrictLogging {
  def prettyPrint(node: PathTrie.TrieNode, indent: String = " "): Unit = {
    val children = node.children.asScala.toList.sortBy(_._1)

    children.foreach {
      case (name, node) =>
        logger.info(s"$indent|_$name")
        prettyPrint(node, indent.padTo(indent.length + 3, ' '))
    }
  }
}
