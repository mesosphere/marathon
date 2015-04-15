package mesosphere.marathon.state

import mesosphere.marathon.state.PathId._
import org.scalatest.{ FunSpec, GivenWhenThen, Matchers }

import scala.collection.SortedSet

class PathIdTest extends FunSpec with GivenWhenThen with Matchers {

  describe("A PathId") {

    it("can be parsed from string") {
      Given("A base id")
      val path = PathId("/a/b/c/d")

      When("The same path as list")
      val reference = PathId("a" :: "b" :: "c" :: "d" :: Nil)

      Then("the path is equal")
      path should be(reference)
    }

    it("can parse the empty list from empty root string") {
      When("The same path as list")
      val reference = PathId("/")

      Then("the path is equal")
      PathId.empty should be(reference)
    }

    it("can be written and parsed from string") {
      Given("A base id")
      val path = PathId("a/b/c/d")

      When("The same path serialized to string and de-serialized again")
      val reference = PathId(path.toString)

      Then("the path is equal")
      path should be(reference)
    }

    it("can compute the canonical path when path is relative") {
      Given("A base id")
      val id = PathId("/a/b/c/d")

      When("a relative path is canonized")
      val path = PathId("./test/../e/f/g/./../").canonicalPath(id)

      Then("the path is absolute and correct")
      path should be(PathId("/a/b/c/d/e/f"))
    }

    it("can compute the canonical path when path is absolute") {

      When("a relative path is canonized")
      val path = PathId("test/../a/b/c/d/d/../e/f/g/./../").canonicalPath()

      Then("the path is absolute and correct")
      path should be(PathId("/a/b/c/d/e/f"))
    }

    it("can compute the restOf with respect to a given path") {
      Given("A base id")
      val id = PathId("a/b/c")

      When("a rest of a path from a given path")
      val path = PathId("a/b/c/d/e/f").restOf(id)

      Then("the rest path is correct")
      path should be(PathId("d/e/f"))
    }

    it("can append to a path") {
      Given("A base id")
      val id = PathId("/a/b/c")

      When("A path is appended to to the base")
      val path = id.append("/d/e/f".toPath)

      Then("the path is appended correctly")
      path should be(PathId("/a/b/c/d/e/f"))
    }

    it("can give the parent path") {
      Given("base id's")
      val id1 = PathId("/a/b/c")
      val id2 = PathId("/a")
      val id3 = PathId.empty

      When("parent ids get computed")
      val parent1 = id1.parent
      val parent2 = id2.parent
      val parent3 = id3.parent

      Then("the parent path is correct")
      parent1 should be(PathId("/a/b"))
      parent2 should be(PathId.empty)
      parent3 should be(PathId.empty)
    }

    it("can convert to a hostname") {
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

  it("handles root paths") {
    PathId("/").isRoot shouldBe true
    PathId("").isRoot shouldBe true
  }

  describe("An ordered PathID collection") {
    val a = PathId("/a")
    val aa = a / "a"
    val ab = a / "b"
    val ac = a / "c"
    val b = PathId("/b")
    val c = PathId("/c")

    it("can be sorted if all paths are on the same level") {
      SortedSet(a, b, a).toSeq should equal(Seq(a, b))
    }

    it("can be sorted if with paths on different levels") {
      SortedSet(a, b, aa, a).toSeq should equal(Seq(a, aa, b))
    }

    it("can be sorted if it was reversed") {
      SortedSet(c, b, a).toSeq should equal(Seq(a, b, c))
      SortedSet(ac, ab, aa).toSeq should equal(Seq(aa, ab, ac))
    }
  }
}
