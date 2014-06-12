package mesosphere.marathon.state

import mesosphere.marathon.api.v1.AppDefinition
import org.scalatest.{ FunSpec, GivenWhenThen, Matchers }

class GroupTest extends FunSpec with GivenWhenThen with Matchers {

  describe("A Group") {

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

    it("can turn a group with dependencies into a dependency graph") {
      Given("a group with subgroups and dependencies")
      val scaling = ScalingStrategy(0.5, Some(1))
      val current: Group = Group("test", scaling, groups = Seq(
        Group("test/database", scaling, groups = Seq(
          Group("test/database/redis", scaling, Seq(AppDefinition("redis"))),
          Group("test/database/memcache", scaling, Seq(AppDefinition("memcache")), dependencies = Seq("test/database/mongo", "test/database/redis")),
          Group("test/database/mongo", scaling, Seq(AppDefinition("mongo")), dependencies = Seq("test/database/redis"))
        )),
        Group("test/service", scaling, groups = Seq(
          Group("test/service/service1", scaling, Seq(AppDefinition("srv1")), dependencies = Seq("test/database/memcache")),
          Group("test/service/service2", scaling, Seq(AppDefinition("srv2")), dependencies = Seq("test/database", "test/service/service1"))
        )),
        Group("test/frontend", scaling, groups = Seq(
          Group("test/frontend/app1", scaling, Seq(AppDefinition("app1")), dependencies = Seq("test/service/service2")),
          Group("test/frontend/app2", scaling, Seq(AppDefinition("app2")), dependencies = Seq("test/service", "test/database/mongo", "test/frontend/app1"))
        )),
        Group("test/cache", scaling, groups = Seq(
          Group("test/cache/c1", scaling, Seq(AppDefinition("cache1"))) //has no dependencies
        ))
      ))
      current.hasNonCyclicDependencies should equal(true)

      When("The application dependency list")
      val (dependent, independent) = current.dependencyList
      val ids = dependent.map(_.id)

      Then("The dependency list is correct")
      ids should have size 7
      ids should not contain GroupId("test/cache/c1")
      val expected = List[GroupId](
        "test/database/redis",
        "test/database/mongo",
        "test/database/memcache",
        "test/service/service1",
        "test/service/service2",
        "test/frontend/app1",
        "test/frontend/app2")
      ids should be(expected)
      independent should have size 1

    }

    it("can turn a group without dependencies into a single step u") {
      Given("a group with subgroups and dependencies")
      val scaling = ScalingStrategy(0.5, Some(1))
      val current: Group = Group("test", scaling, groups = Seq(
        Group("test/database", scaling, groups = Seq(
          Group("test/database/redis", scaling, Seq(AppDefinition("redis"))),
          Group("test/database/memcache", scaling, Seq(AppDefinition("memcache"))),
          Group("test/database/mongo", scaling, Seq(AppDefinition("mongo")))
        )),
        Group("test/service", scaling, groups = Seq(
          Group("test/service/service1", scaling, Seq(AppDefinition("srv1"))),
          Group("test/service/service2", scaling, Seq(AppDefinition("srv2")))
        )),
        Group("test/frontend", scaling, groups = Seq(
          Group("test/frontend/app1", scaling, Seq(AppDefinition("app1"))),
          Group("test/frontend/app2", scaling, Seq(AppDefinition("app2")))
        )),
        Group("test/cache", scaling, groups = Seq(
          Group("test/cache/c1", scaling, Seq(AppDefinition("cache1")))
        ))
      ))
      current.hasNonCyclicDependencies should equal(true)

      When("The application dependency list")
      val (dependent, independent) = current.dependencyList

      Then("The dependency list is correct")
      dependent should have size 0
      independent should have size 8

    }

    it("can not compute the dependencies, if the dependency graph is not strictly acyclic") {
      Given("a group with cycled dependencies")
      val scaling = ScalingStrategy(0.5, Some(1))
      val current: Group = Group("test", scaling, groups = Seq(
        Group("test/database", scaling, groups = Seq(
          Group("test/database/mongo", scaling, Seq(AppDefinition("mongo")), dependencies = Seq("test/service"))
        )),
        Group("test/service", scaling, groups = Seq(
          Group("test/service/service1", scaling, Seq(AppDefinition("srv1")), dependencies = Seq("test/database"))
        ))
      ))
      current.hasNonCyclicDependencies should equal(false)

      When("The application dependency list can not be computed")
      val exception = intercept[IllegalArgumentException] {
        current.dependencyList
      }

      Then("An exception is thrown")
    }
  }
}
