package mesosphere.marathon
package state

import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.state.PathId._

import scala.collection.SortedSet

class PathIdTest extends UnitTest with ValidationTestLike {
  "A PathId" can {
    "be parsed from string" in {
      Given("A base id")
      val path = PathId("/a/b/c/d")

      When("The same path as list")
      val reference = PathId("a" :: "b" :: "c" :: "d" :: Nil)

      Then("the path is equal")
      path should be(reference)
    }

    "parse the empty list from empty root string" in {
      When("The same path as list")
      val reference = PathId("/")

      Then("the path is equal")
      PathId.empty should be(reference)
    }

    "parse safePath from itself" in {
      When("The path is empty")
      PathId.fromSafePath(PathId.empty.safePath) should equal(PathId.empty)

      When("The path isn't empty")
      val reference = PathId("a" :: "b" :: "c" :: "d" :: Nil)
      PathId.fromSafePath(reference.safePath) should equal(reference)
    }

    "be written and parsed from string" in {
      Given("A base id")
      val path = PathId("a/b/c/d")

      When("The same path serialized to string and de-serialized again")
      val reference = PathId(path.toString)

      Then("the path is equal")
      path should be(reference)
    }

    "compute the canonical path when path is relative" in {
      Given("A base id")
      val id = PathId("/a/b/c/d")

      When("a relative path is canonized")
      val path = PathId("./test/../e/f/g/./../").canonicalPath(id)

      Then("the path is absolute and correct")
      path should be(PathId("/a/b/c/d/e/f"))
    }

    "compute the canonical path when path is absolute" in {

      When("a relative path is canonized")
      val path = PathId("test/../a/b/c/d/d/../e/f/g/./../").canonicalPath()

      Then("the path is absolute and correct")
      path should be(PathId("/a/b/c/d/e/f"))
    }

    "compute the restOf with respect to a given path" in {
      Given("A base id")
      val id = PathId("a/b/c")

      When("a rest of a path from a given path")
      val path = PathId("a/b/c/d/e/f").restOf(id)

      Then("the rest path is correct")
      path should be(PathId("d/e/f"))
    }

    "append to a path" in {
      Given("A base id")
      val id = PathId("/a/b/c")

      When("A path is appended to to the base")
      val path = id.append("/d/e/f".toPath)

      Then("the path is appended correctly")
      path should be(PathId("/a/b/c/d/e/f"))
    }

    "give the taskTrackerRef path" in {
      Given("base id's")
      val id1 = PathId("/a/b/c")
      val id2 = PathId("/a")
      val id3 = PathId.empty

      When("taskTrackerRef ids get computed")
      val parent1 = id1.parent
      val parent2 = id2.parent
      val parent3 = id3.parent

      Then("the taskTrackerRef path is correct")
      parent1 should be(PathId("/a/b"))
      parent2 should be(PathId.empty)
      parent3 should be(PathId.empty)
    }

    "convert to a hostname" in {
      Given("base id's")
      val id1 = PathId("/a/b/c")
      val id2 = PathId("/a")
      val id3 = PathId.empty

      When("hostnames get computed")
      val host1 = id1.toHostname
      val host2 = id2.toHostname
      val host3 = id3.toHostname

      Then("the hostname is valid")
      host1 should be("c.b.a")
      host2 should be("a")
      host3 should be("")
    }
  }
  "PathIds" should {
    "handles root paths" in {
      PathId("/").isRoot shouldBe true
      PathId("").isRoot shouldBe true
    }

    "match another PathId" in {
      PathId("/a/b/c").includes(PathId("/a/b")) shouldBe true
      PathId("/a/b/c").includes(PathId("/a/b/d")) shouldBe false
      PathId("/a/b/c").includes(PathId("/a")) shouldBe true
      PathId("/a/b/c").includes(PathId("/other")) shouldBe false
    }

    "give all parents as sequence" in {
      val parents = PathId("/a/b/c/d").allParents
      parents should be(Seq(PathId("/a/b/c"), PathId("/a/b"), PathId("/a"), PathId("/")))
      parents should have size 4
    }
  }

  "An ordered PathID collection" should {
    val a = PathId("/a")
    val aa = a / "a"
    val ab = a / "b"
    val ac = a / "c"
    val b = PathId("/b")
    val c = PathId("/c")

    "be sorted if all paths are on the same level" in {
      SortedSet(a, b, a).toSeq should equal(Seq(a, b))
    }

    "be sorted if with paths on different levels" in {
      SortedSet(a, b, aa, a).toSeq should equal(Seq(a, aa, b))
    }

    "be sorted if it was reversed" in {
      SortedSet(c, b, a).toSeq should equal(Seq(a, b, c))
      SortedSet(ac, ab, aa).toSeq should equal(Seq(aa, ab, ac))
    }
  }

  "The PathId validation" when {

    "passed legal characters" should {
      "be valid" in {
        val path = PathId("/foobar-0")
        pathIdValidator(path) shouldBe aSuccess
      }
    }

    "passed illegal characters" should {
      "be invalid" in {
        val path = PathId("/@§\'foobar-0")
        pathIdValidator(path) should haveViolations(
          "/" -> "must fully match regular expression '^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])|(\\.|\\.\\.)$'")
      }
    }
  }
}
