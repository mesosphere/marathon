package mesosphere.marathon.state

import mesosphere.marathon.state.PathId._
import org.scalatest.{ FunSpec, GivenWhenThen, Matchers }

import scala.collection.immutable.Seq

class GroupTest extends FunSpec with GivenWhenThen with Matchers {

  describe("A Group") {

    it("can find a group by its path") {
      Given("an existing group with two subgroups")
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Set(AppDefinition("/test/group1/app1".toPath))),
          Group("/test/group2".toPath, Set(AppDefinition("/test/group2/app2".toPath)))
        ))))

      When("a group with a specific path is requested")
      val path = PathId("/test/group1")

      Then("the group is found")
      current.group(path) should be('defined)
    }

    it("can not find a group if its not existing") {
      Given("an existing group with two subgroups")
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Set(AppDefinition("/test/group1/app1".toPath))),
          Group("/test/group2".toPath, Set(AppDefinition("/test/group2/app2".toPath)))
        ))))

      When("a group with a specific path is requested")
      val path = PathId("/test/unknown")

      Then("the group is not found")
      current.group(path) should be('empty)
    }

    it("can do an update by applying a change function") {
      Given("an existing group with two subgroups")
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Set(AppDefinition("/test/group1/app1".toPath))),
          Group("/test/group2".toPath, Set(AppDefinition("/test/group2/app2".toPath)))
        ))))

      When("the group will be updated")
      val timestamp = Timestamp.now()
      val result = current.update(timestamp) { group =>
        if (group.id == PathId("/test/group2"))
          Group("/test/group3".toPath, Set(AppDefinition("app2".toPath)), version = timestamp)
        else group
      }

      Then("the update has been applied")
      result.version should be(timestamp)
      result.group("/test/group3".toPath) should be('defined)
      result.group("/test/group3".toPath).get.version should be(timestamp)
      result.group("/test".toPath).get.version should be(timestamp)
    }

    it("can do an update by applying a change function with a path identifier") {
      Given("an existing group with two subgroups")
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Set(AppDefinition("/test/group1/app1".toPath))),
          Group("/test/group2".toPath, Set(AppDefinition("/test/group2/app2".toPath)))
        ))))

      When("the group will be updated")
      val timestamp = Timestamp.now()
      def change(group: Group) = Group("/test/group3".toPath, Set(AppDefinition("app2".toPath)), version = timestamp)

      val result = current.update(PathId("/test/group2"), change, timestamp)

      Then("the update has been applied")
      result.version should be(timestamp)
      result.group("/test/group3".toPath) should be('defined)
      result.group("/test/group3".toPath).get.version should be(timestamp)
      result.group("/test".toPath).get.version should be(timestamp)
    }

    it("can delete a node based in the path") {
      Given("an existing group with two subgroups")
      val current = Group.empty.makeGroup("/test/foo/one".toPath).makeGroup("/test/bla/two".toPath)

      When("a node will be deleted based on path")
      val group = current.remove("/test/foo".toPath)

      Then("the update has been applied")
      group.group("/test/foo".toPath) should be('empty)
      group.group("/test/bla".toPath) should be('defined)
    }

    it("can make groups specified by a path") {
      Given("a group with subgroups")
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Set(AppDefinition("/test/group1/app1".toPath))),
          Group("/test/group2".toPath, Set(AppDefinition("/test/group2/app2".toPath)))
        ))))

      When("a non existing path is requested")
      val path = PathId("/test/group3/group4/group5")
      val group = current.makeGroup(path)

      Then("the path has been created")
      group.group(path) should be('defined)

      When("a partly existing path is requested")
      val path2 = PathId("/test/group1/group4/group5")
      val group2 = current.makeGroup(path2)

      Then("only the missing path has been created")
      group2.group(path2) should be('defined)

      When("the path is already existent")
      val path3 = PathId("/test/group1")
      val group3 = current.makeGroup(path3)

      Then("nothing has been changed")
      group3 should equal(current)
    }

    it("can marshal and unmarshal from to protos") {
      Given("a group with subgroups")
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Set(AppDefinition("/test/group1/app1".toPath, args = Some(Seq("a", "b", "c"))))),
          Group("/test/group2".toPath, Set(AppDefinition("/test/group2/app2".toPath, args = Some(Seq("a", "b")))))
        ))))

      When("the group is marshalled and unmarshalled again")
      val group = Group.fromProto(current.toProto)

      Then("the groups are identical")
      group should equal(current)
    }

    it("can turn a group with group dependencies into a dependency graph") {
      Given("a group with subgroups and dependencies")
      val current: Group = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/database".toPath, groups = Set(
            Group("/test/database/redis".toPath, Set(AppDefinition("/test/database/redis/r1".toPath))),
            Group("/test/database/memcache".toPath, Set(AppDefinition("/test/database/memcache/c1".toPath)), dependencies = Set("/test/database/mongo".toPath, "/test/database/redis".toPath)),
            Group("/test/database/mongo".toPath, Set(AppDefinition("/test/database/mongo/m1".toPath)), dependencies = Set("/test/database/redis".toPath))
          )),
          Group("/test/service".toPath, groups = Set(
            Group("/test/service/service1".toPath, Set(AppDefinition("/test/service/service1/s1".toPath)), dependencies = Set("/test/database/memcache".toPath)),
            Group("/test/service/service2".toPath, Set(AppDefinition("/test/service/service2/s2".toPath)), dependencies = Set("/test/database".toPath, "/test/service/service1".toPath))
          )),
          Group("/test/frontend".toPath, groups = Set(
            Group("/test/frontend/app1".toPath, Set(AppDefinition("/test/frontend/app1/a1".toPath)), dependencies = Set("/test/service/service2".toPath)),
            Group("/test/frontend/app2".toPath, Set(AppDefinition("/test/frontend/app2/a2".toPath)), dependencies = Set("/test/service".toPath, "/test/database/mongo".toPath, "/test/frontend/app1".toPath))
          )),
          Group("/test/cache".toPath, groups = Set(
            Group("/test/cache/c1".toPath, Set(AppDefinition("/test/cache/c1/c1".toPath))) //has no dependencies
          ))
        ))))
      current.hasNonCyclicDependencies should equal(true)

      When("The application dependency list")
      val (dependent, independent) = current.dependencyList
      val ids = dependent.map(_.id)

      Then("The dependency list is correct")
      ids should have size 7
      ids should not contain PathId("/test/cache/c1")
      val expected = List[PathId](
        "/test/database/redis/r1".toPath,
        "/test/database/mongo/m1".toPath,
        "/test/database/memcache/c1".toPath,
        "/test/service/service1/s1".toPath,
        "/test/service/service2/s2".toPath,
        "/test/frontend/app1/a1".toPath,
        "/test/frontend/app2/a2".toPath)
      ids should be(expected)
      independent should have size 1
    }

    it("can turn a group with app dependencies into a dependency graph") {
      Given("a group with subgroups and dependencies")
      val current: Group = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/database".toPath, Set(
            AppDefinition("/test/database/redis".toPath),
            AppDefinition("/test/database/memcache".toPath, dependencies = Set("/test/database/mongo".toPath, "/test/database/redis".toPath)),
            AppDefinition("/test/database/mongo".toPath, dependencies = Set("/test/database/redis".toPath))
          )),
          Group("/test/service".toPath, Set(
            AppDefinition("/test/service/srv1".toPath, dependencies = Set("/test/database/memcache".toPath)),
            AppDefinition("/test/service/srv2".toPath, dependencies = Set("/test/database/mongo".toPath, "/test/service/srv1".toPath))
          )),
          Group("/test/frontend".toPath, Set(
            AppDefinition("/test/frontend/app1".toPath, dependencies = Set("/test/service/srv2".toPath)),
            AppDefinition("/test/frontend/app2".toPath, dependencies = Set("/test/service/srv2".toPath, "/test/database/mongo".toPath, "/test/frontend/app1".toPath))
          )),
          Group("/test/cache".toPath, Set(
            AppDefinition("/test/cache/cache1".toPath) //has no dependencies
          ))))
      ))
      current.hasNonCyclicDependencies should equal(true)

      When("The application dependency list")
      val (dependent, independent) = current.dependencyList
      val ids = dependent.map(_.id)

      Then("The dependency list is correct")
      ids should have size 7
      ids should not contain PathId("/test/cache/c1")
      val expected = List[PathId](
        "/test/database/redis".toPath,
        "/test/database/mongo".toPath,
        "/test/database/memcache".toPath,
        "/test/service/srv1".toPath,
        "/test/service/srv2".toPath,
        "/test/frontend/app1".toPath,
        "/test/frontend/app2".toPath)
      ids should be(expected)
      independent should have size 1

    }

    it("can turn a group without dependencies into a single step plan") {
      Given("a group with subgroups and dependencies")
      val current: Group = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/database".toPath, groups = Set(
            Group("/test/database/redis".toPath, Set(AppDefinition("/test/database/redis/r1".toPath))),
            Group("/test/database/memcache".toPath, Set(AppDefinition("/test/database/memcache/m1".toPath))),
            Group("/test/database/mongo".toPath, Set(AppDefinition("/test/database/mongo/m1".toPath)))
          )),
          Group("/test/service".toPath, groups = Set(
            Group("/test/service/service1".toPath, Set(AppDefinition("/test/service/service1/srv1".toPath))),
            Group("/test/service/service2".toPath, Set(AppDefinition("/test/service/service2/srv2".toPath)))
          )),
          Group("/test/frontend".toPath, groups = Set(
            Group("/test/frontend/app1".toPath, Set(AppDefinition("/test/frontend/app1/a1".toPath))),
            Group("/test/frontend/app2".toPath, Set(AppDefinition("/test/frontend/app2/a2".toPath)))
          )),
          Group("/test/cache".toPath, groups = Set(
            Group("/test/cache/c1".toPath, Set(AppDefinition("/test/cache/c1/cache1".toPath)))
          ))
        ))))
      current.hasNonCyclicDependencies should equal(true)

      When("The application dependency list")
      val (dependent, independent) = current.dependencyList

      Then("The dependency list is correct")
      dependent should have size 0
      independent should have size 8
    }

    it("can not compute the dependencies, if the dependency graph is not strictly acyclic") {
      Given("a group with cycled dependencies")
      val current: Group = Group("/test".toPath, groups = Set(
        Group("/test/database".toPath, groups = Set(
          Group("/test/database/mongo".toPath, Set(AppDefinition("/test/database/mongo/m1".toPath, dependencies = Set("/test/service".toPath))))
        )),
        Group("/test/service".toPath, groups = Set(
          Group("/test/service/service1".toPath, Set(AppDefinition("/test/service/service1/srv1".toPath, dependencies = Set("/test/database".toPath))))
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
