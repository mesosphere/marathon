package mesosphere.marathon.state

import org.scalatest.{ FunSpec, Matchers, GivenWhenThen, FunSuite }
import mesosphere.marathon.api.v1.AppDefinition

class GroupTest extends FunSpec with GivenWhenThen with Matchers {

  describe("A group") {

    it("can find a group by its path") {
      Given("an existing group with two subgroups")
      val scaling = ScalingStrategy(0.5, Some(1))
      val current = Group("test", scaling, groups = Seq(
        Group("test/group1", scaling, Seq(AppDefinition("app1"))),
        Group("test/group2", scaling, Seq(AppDefinition("app2")))
      ))

      When("a group with a specific path is requested")
      val path = GroupId("test/group1")

      Then("the group is found")
      current.group(path) should be('defined)
    }

    it("can not find a group if its not existing") {
      Given("an existing group with two subgroups")
      val scaling = ScalingStrategy(0.5, Some(1))
      val current = Group("test", scaling, groups = Seq(
        Group("test/group1", scaling, Seq(AppDefinition("app1"))),
        Group("test/group2", scaling, Seq(AppDefinition("app2")))
      ))

      When("a group with a specific path is requested")
      val path = GroupId("test/unknown")

      Then("the group is not found")
      current.group(path) should be('empty)
    }

    it("can do an update by applying a change function") {
      Given("an existing group with two subgroups")
      val scaling = ScalingStrategy(0.5, Some(1))
      val current = Group("test", scaling, groups = Seq(
        Group("test/group1", scaling, Seq(AppDefinition("app1"))),
        Group("test/group2", scaling, Seq(AppDefinition("app2")))
      ))

      When("the group will be updated")
      val timestamp = Timestamp.now()
      val result = current.update(timestamp) { group =>
        if (group.id.toString != "test/group2") group
        else {
          Group("test/group3", scaling, Seq(AppDefinition("app2")), version = timestamp)
        }
      }

      Then("the update has been applied")
      result.version should be(timestamp)
      result.groups should have size 2
      result.groups(1).id.toString should be("test/group3")
      result.groups(1).version should be(timestamp)
      result.groups(0).version should be(timestamp)
    }

    it("can delete a node based in the path") {
      Given("an existing group with two subgroups")
      val current = Group("test", ScalingStrategy(0.5, Some(1))).makeGroup("test/foo/one").makeGroup("test/bla/two")

      When("a node will be deleted based on path")
      val group = current.remove("test/foo")

      Then("the update has been applied")
      group.group("test/foo") should be('empty)
      group.group("test/bla") should be('defined)
    }

    it("can make groups specified by a path") {
      Given("a group with subgroups")
      val scaling = ScalingStrategy(0.5, Some(1))
      val current = Group("test", scaling, groups = Seq(
        Group("test/group1", scaling, Seq(AppDefinition("app1"))),
        Group("test/group2", scaling, Seq(AppDefinition("app2")))
      ))

      When("a non existing path is requested")
      val path = GroupId("test/group3/group4/group5")
      val group = current.makeGroup(path)

      Then("the path has been created")
      group.group(path) should be('defined)

      When("a partly existing path is requested")
      val path2 = GroupId("test/group1/group4/group5")
      val group2 = current.makeGroup(path2)

      Then("only the missing path has been created")
      group2.group(path2) should be('defined)

      When("the path is already existent")
      val path3 = GroupId("test/group1")
      val group3 = current.makeGroup(path3)

      Then("nothing has been changed")
      group3 should equal(current)
    }

    it("can marshal and unmarshal from to protos") {
      Given("a group with subgroups")
      val scaling = ScalingStrategy(0.5, Some(1))
      val current = Group("test", scaling, groups = Seq(
        Group("test/group1", scaling, Seq(AppDefinition("app1"))),
        Group("test/group2", scaling, Seq(AppDefinition("app2")))
      ))

      When("the group is marshalled and unmarshalled again")
      val group = Group.fromProto(current.toProto)

      Then("the groups are identical")
      group should equal(current)
    }
  }
}
