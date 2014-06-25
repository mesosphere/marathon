package mesosphere.marathon.state

import mesosphere.marathon.state.PathId._
import org.scalatest.{ FunSpec, GivenWhenThen, Matchers }

class PathIdTest extends FunSpec with GivenWhenThen with Matchers {

  describe("A GroupId") {

    it("can be parsed from string") {
      Given("A base id")
      val group = PathId("/a/b/c/d")

      When("The same path as list")
      val reference = PathId("a" :: "b" :: "c" :: "d" :: Nil)

      Then("the path is equal")
      group should be(reference)
    }

    it("can parse the empty list from empty root string") {
      When("The same path as list")
      val reference = PathId("/")

      Then("the path is equal")
      PathId.empty should be(reference)
    }

    it("can be written and parsed from string") {
      Given("A base id")
      val group = PathId("a/b/c/d")

      When("The same path serialized to string and de-serialized again")
      val reference = PathId(group.toString)

      Then("the path is equal")
      group should be(reference)
    }

    it("can compute the canonical path when path is relative") {
      Given("A base id")
      val id = PathId("/a/b/c/d")

      When("a relative path is canonized")
      val group = PathId("./test/../e/f/g/./../").canonicalPath(id)

      Then("the path is absolute and correct")
      group should be(PathId("/a/b/c/d/e/f"))
    }

    it("can compute the canonical path when path is absolute") {

      When("a relative path is canonized")
      val group = PathId("test/../a/b/c/d/d/../e/f/g/./../").canonicalPath()

      Then("the path is absolute and correct")
      group should be(PathId("/a/b/c/d/e/f"))
    }

    it("can compute the restOf with respect to a given path") {
      Given("A base id")
      val id = PathId("a/b/c")

      When("a relative path is canonized")
      val group = PathId("a/b/c/d/e/f").restOf(id)

      Then("the path is absolute and correct")
      group should be(PathId("d/e/f"))
    }

    it("can append to a path") {
      Given("A base id")
      val id = PathId("/a/b/c")

      When("a relative path is canonized")
      val group = id.append("/d/e/f".toPath)

      Then("the path is absolute and correct")
      group should be(PathId("/a/b/c/d/e/f"))
    }

    it("can give the parent path") {
      Given("A base id")
      val id1 = PathId("/a/b/c")
      val id2 = PathId("/a")
      val id3 = PathId.empty

      When("a relative path is canonized")
      val parent1 = id1.parent
      val parent2 = id2.parent
      val parent3 = id3.parent

      Then("the path is absolute and correct")
      parent1 should be(PathId("/a/b"))
      parent2 should be(PathId.empty)
      parent3 should be(PathId.empty)
    }
  }
}
