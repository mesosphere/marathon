package mesosphere.marathon.state

import com.wix.accord._
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.state.AppDefinition.VersionInfo
import mesosphere.marathon.state.PathId._
import org.scalatest.{ FunSpec, GivenWhenThen, Matchers }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

class GroupTest extends FunSpec with GivenWhenThen with Matchers {

  describe("A Group") {

    it("can find a group by its path") {
      Given("an existing group with two subgroups")
      val app1 = AppDefinition("/test/group1/app1".toPath)
      val app2 = AppDefinition("/test/group2/app2".toPath)
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Map(app1.id -> app1)),
          Group("/test/group2".toPath, Map(app2.id -> app2))
        ))))

      When("a group with a specific path is requested")
      val path = PathId("/test/group1")

      Then("the group is found")
      current.group(path) should be('defined)
    }

    it("can not find a group if its not existing") {
      Given("an existing group with two subgroups")
      val app1 = AppDefinition("/test/group1/app1".toPath)
      val app2 = AppDefinition("/test/group2/app2".toPath)
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Map(app1.id -> app1)),
          Group("/test/group2".toPath, Map(app2.id -> app2))
        ))))

      When("a group with a specific path is requested")
      val path = PathId("/test/unknown")

      Then("the group is not found")
      current.group(path) should be('empty)
    }

    it("can filter a group by a filter function") {
      Given("an group with subgroups")
      val group1App1 = AppDefinition("/test/group1/app1".toPath)
      val group2App2 = AppDefinition("/test/group2/app2".toPath)
      val group2AApp1 = AppDefinition("/test/group2/a/app1".toPath)
      val group2BApp1 = AppDefinition("/test/group2/b/app1".toPath)
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Map(group1App1.id -> group1App1)),
          Group("/test/group2".toPath, Map(group2App2.id -> group2App2), Set(
            Group("/test/group2/a".toPath, Map(group2AApp1.id -> group2AApp1)),
            Group("/test/group2/b".toPath, Map(group2BApp1.id -> group2BApp1))
          ))
        ))))

      When("a group with a specific path is requested")
      val allowed = "/test/group2/a".toPath
      val updated = current.updateGroup { group =>
        if (group.id.includes(allowed)) Some(group) //child
        else if (allowed.includes(group.id)) Some(group.copy(apps = Map.empty, dependencies = Set.empty)) //taskTrackerRef
        else None
      }

      Then("the group is not found")
      updated should be('defined)
      updated.get.group("/test/group1".toPath) should be('empty)
      updated.get.group("/test".toPath) should be('defined)
      updated.get.group("/test/group2".toPath) should be('defined)
      updated.get.group("/test/group2/a".toPath) should be('defined)
      updated.get.group("/test/group2/b".toPath) should be('empty)
    }

    it("can do an update by applying a change function") {
      Given("an existing group with two subgroups")
      val app1 = AppDefinition("/test/group1/app1".toPath)
      val app2 = AppDefinition("/test/group2/app2".toPath)
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Map(app1.id -> app1)),
          Group("/test/group2".toPath, Map(app2.id -> app2))
        ))))

      When("the group will be updated")
      val timestamp = Timestamp.now()
      val relativeApp2 = AppDefinition("app2".toPath)
      val result = current.update(timestamp) { group =>
        if (group.id == PathId("/test/group2"))
          Group("/test/group3".toPath, Map(relativeApp2.id -> relativeApp2), version = timestamp)
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
      val app1 = AppDefinition("/test/group1/app1".toPath)
      val app2 = AppDefinition("/test/group2/app2".toPath)
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Map(app1.id -> app2)),
          Group("/test/group2".toPath, Map(app2.id -> app2))
        ))))

      When("the group will be updated")
      val timestamp = Timestamp.now()
      val relativeApp2 = AppDefinition("app2".toPath)
      def change(group: Group) = Group("/test/group3".toPath, Map(relativeApp2.id -> relativeApp2), version = timestamp)

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
      val app1 = AppDefinition("/test/group1/app1".toPath)
      val app2 = AppDefinition("/test/group2/app2".toPath)
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Map(app1.id -> app1)),
          Group("/test/group2".toPath, Map(app2.id -> app2))
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

    it("can replace a group without apps by an app definition") {
      // See https://github.com/mesosphere/marathon/issues/851
      // Groups are created implicitly by creating apps and are not visible as separate entities
      // at the time of the creation of this test/issue. They are only visible in the GUI if they contain apps.

      Given("an existing group /some/nested which does not directly or indirectly contain apps")
      val current =
        Group
          .empty
          .makeGroup("/some/nested/path".toPath)
          .makeGroup("/some/nested/path2".toPath)

      current.transitiveGroups.map(_.id.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))

      When("requesting to put an app definition")
      val changed = current.updateApp(
        "/some/nested".toPath,
        _ => AppDefinition("/some/nested".toPath, cmd = Some("true")),
        Timestamp.now())

      Then("the group with same path has been replaced by the new app definition")
      changed.transitiveGroups.map(_.id.toString) should be(Set("/", "/some"))
      changed.transitiveApps.map(_.id.toString) should be(Set("/some/nested"))

      Then("the resulting group should be valid when represented in the V2 API model")
      validate(changed)(Group.validRootGroup(maxApps = None)) should be (Success)
    }

    it("cannot replace a group with apps by an app definition") {
      Given("an existing group /some/nested which does contain an app")
      val current =
        Group
          .empty
          .makeGroup("/some/nested/path".toPath)
          .makeGroup("/some/nested/path2".toPath)
          .updateApp(
            "/some/nested/path2/app".toPath,
            _ => AppDefinition("/some/nested/path2/app".toPath, cmd = Some("true")),
            Timestamp.now())

      current.transitiveGroups.map(_.id.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))

      When("requesting to put an app definition")
      val changed = current.updateApp(
        "/some/nested".toPath,
        _ => AppDefinition("/some/nested".toPath, cmd = Some("true")),
        Timestamp.now())

      Then("the group with same path has NOT been replaced by the new app definition")
      current.transitiveGroups.map(_.id.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))
      changed.transitiveApps.map(_.id.toString) should be(Set("/some/nested", "/some/nested/path2/app"))

      Then("the conflict will be detected by our V2 API model validation")
      val result = validate(changed)(Group.validRootGroup(maxApps = None))
      result.isFailure should be(true)
      ValidationHelper.getAllRuleConstrains(result).head
        .message should be ("Groups and Applications may not have the same identifier.")
    }

    it("can marshal and unmarshal from to protos") {
      Given("a group with subgroups")
      val now = Timestamp(11)
      val fullVersion = VersionInfo.forNewConfig(now)
      val app1 = AppDefinition("/test/group1/app1".toPath, args = Some(Seq("a", "b", "c")), versionInfo = fullVersion)
      val app2 = AppDefinition("/test/group2/app2".toPath, args = Some(Seq("a", "b")), versionInfo = fullVersion)
      val current = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/group1".toPath, Map(app1.id -> app1)),
          Group("/test/group2".toPath, Map(app2.id -> app2))
        ))))

      When("the group is marshalled and unmarshalled again")
      val group = Group.fromProto(current.toProto)

      Then("the groups are identical")
      group should equal(current)
    }

    it("can turn a group with group dependencies into a dependency graph") {
      Given("a group with subgroups and dependencies")
      val redisApp = AppDefinition("/test/database/redis/r1".toPath)
      val memcacheApp = AppDefinition("/test/database/memcache/c1".toPath)
      val mongoApp = AppDefinition("/test/database/mongo/m1".toPath)
      val serviceApp1 = AppDefinition("/test/service/service1/s1".toPath)
      val serviceApp2 = AppDefinition("/test/service/service2/s2".toPath)
      val frontendApp1 = AppDefinition("/test/frontend/app1/a1".toPath)
      val frontendApp2 = AppDefinition("/test/frontend/app2/a2".toPath)
      val cacheApp = AppDefinition("/test/cache/c1/c1".toPath)
      val current: Group = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/database".toPath, groups = Set(
            Group("/test/database/redis".toPath, Map(redisApp.id -> redisApp)),
            Group("/test/database/memcache".toPath, Map(memcacheApp.id -> memcacheApp), dependencies = Set("/test/database/mongo".toPath, "/test/database/redis".toPath)),
            Group("/test/database/mongo".toPath, Map(mongoApp.id -> mongoApp), dependencies = Set("/test/database/redis".toPath))
          )),
          Group("/test/service".toPath, groups = Set(
            Group("/test/service/service1".toPath, Map(serviceApp1.id -> serviceApp1), dependencies = Set("/test/database/memcache".toPath)),
            Group("/test/service/service2".toPath, Map(serviceApp2.id -> serviceApp2), dependencies = Set("/test/database".toPath, "/test/service/service1".toPath))
          )),
          Group("/test/frontend".toPath, groups = Set(
            Group("/test/frontend/app1".toPath, Map(frontendApp1.id -> frontendApp1), dependencies = Set("/test/service/service2".toPath)),
            Group("/test/frontend/app2".toPath, Map(frontendApp2.id -> frontendApp2), dependencies = Set("/test/service".toPath, "/test/database/mongo".toPath, "/test/frontend/app1".toPath))
          )),
          Group("/test/cache".toPath, groups = Set(
            Group("/test/cache/c1".toPath, Map(cacheApp.id -> cacheApp)) //has no dependencies
          ))
        ))))
      current.hasNonCyclicDependencies should equal(true)

      When("the dependency graph is computed")
      val dependencyGraph = current.dependencyGraph
      val ids: Set[PathId] = dependencyGraph.vertexSet.asScala.map(_.id).toSet

      Then("the dependency graph is correct")
      ids should have size 8

      val expectedIds = Set[PathId](
        "/test/database/redis/r1".toPath,
        "/test/database/mongo/m1".toPath,
        "/test/database/memcache/c1".toPath,
        "/test/service/service1/s1".toPath,
        "/test/service/service2/s2".toPath,
        "/test/frontend/app1/a1".toPath,
        "/test/frontend/app2/a2".toPath,
        "/test/cache/c1/c1".toPath
      )
      ids should equal (expectedIds)

      current.appsWithNoDependencies should have size 2
    }

    it("can turn a group with app dependencies into a dependency graph") {
      Given("a group with subgroups and dependencies")
      val redisApp = AppDefinition("/test/database/redis".toPath)
      val memcacheApp = AppDefinition("/test/database/memcache".toPath, dependencies = Set("/test/database/mongo".toPath, "/test/database/redis".toPath))
      val mongoApp = AppDefinition("/test/database/mongo".toPath, dependencies = Set("/test/database/redis".toPath))
      val serviceApp1 = AppDefinition("/test/service/srv1".toPath, dependencies = Set("/test/database/memcache".toPath))
      val serviceApp2 = AppDefinition("/test/service/srv2".toPath, dependencies = Set("/test/database/mongo".toPath, "/test/service/srv1".toPath))
      val frontendApp1 = AppDefinition("/test/frontend/app1".toPath, dependencies = Set("/test/service/srv2".toPath))
      val frontendApp2 = AppDefinition("/test/frontend/app2".toPath, dependencies = Set("/test/service/srv2".toPath, "/test/database/mongo".toPath, "/test/frontend/app1".toPath))
      val cacheApp = AppDefinition("/test/cache/cache1".toPath) //has no dependencies
      val current: Group = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/database".toPath, Map(
            redisApp.id -> redisApp,
            memcacheApp.id -> memcacheApp,
            mongoApp.id -> mongoApp
          )),
          Group("/test/service".toPath, Map(
            serviceApp1.id -> serviceApp1,
            serviceApp2.id -> serviceApp2
          )),
          Group("/test/frontend".toPath, Map(
            frontendApp1.id -> frontendApp1,
            frontendApp2.id -> frontendApp2
          )),
          Group("/test/cache".toPath, Map(cacheApp.id -> cacheApp))))
      ))
      current.hasNonCyclicDependencies should equal(true)

      When("the dependency graph is calculated")
      val dependencyGraph = current.dependencyGraph
      val ids: Set[PathId] = dependencyGraph.vertexSet.asScala.map(_.id).toSet

      Then("the dependency graph is correct")
      ids should have size 8
      ids should not contain PathId("/test/cache/c1")
      val expected = Set[PathId](
        "/test/database/redis".toPath,
        "/test/database/mongo".toPath,
        "/test/database/memcache".toPath,
        "/test/service/srv1".toPath,
        "/test/service/srv2".toPath,
        "/test/frontend/app1".toPath,
        "/test/frontend/app2".toPath,
        "/test/cache/cache1".toPath
      )
      ids should be(expected)

      current.appsWithNoDependencies should have size 2
    }

    it("can turn a group without dependencies into a dependency graph") {
      Given("a group with subgroups and dependencies")
      val redisApp = AppDefinition("/test/database/redis/r1".toPath)
      val memcacheApp = AppDefinition("/test/database/memcache/m1".toPath)
      val mongoApp = AppDefinition("/test/database/mongo/m1".toPath)
      val serviceApp1 = AppDefinition("/test/service/service1/srv1".toPath)
      val serviceApp2 = AppDefinition("/test/service/service2/srv2".toPath)
      val frontendApp1 = AppDefinition("/test/frontend/app1/a1".toPath)
      val frontendApp2 = AppDefinition("/test/frontend/app2/a2".toPath)
      val cacheApp1 = AppDefinition("/test/cache/c1/cache1".toPath)
      val current: Group = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/database".toPath, groups = Set(
            Group("/test/database/redis".toPath, Map(redisApp.id -> redisApp)),
            Group("/test/database/memcache".toPath, Map(memcacheApp.id -> memcacheApp)),
            Group("/test/database/mongo".toPath, Map(mongoApp.id -> mongoApp))
          )),
          Group("/test/service".toPath, groups = Set(
            Group("/test/service/service1".toPath, Map(serviceApp1.id -> serviceApp1)),
            Group("/test/service/service2".toPath, Map(serviceApp2.id -> serviceApp2))
          )),
          Group("/test/frontend".toPath, groups = Set(
            Group("/test/frontend/app1".toPath, Map(frontendApp1.id -> frontendApp1)),
            Group("/test/frontend/app2".toPath, Map(frontendApp2.id -> frontendApp2))
          )),
          Group("/test/cache".toPath, groups = Set(
            Group("/test/cache/c1".toPath, Map(cacheApp1.id -> cacheApp1))
          ))
        ))))
      current.hasNonCyclicDependencies should equal(true)

      When("the dependency graph is calculated")
      val dependencyGraph = current.dependencyGraph

      Then("the dependency graph is correct")
      current.appsWithNoDependencies should have size 8
    }

    it("detects a cyclic dependency graph") {
      Given("a group with cyclic dependencies")
      val mongoApp = AppDefinition("/test/database/mongo/m1".toPath, dependencies = Set("/test/service".toPath))
      val serviceApp1 = AppDefinition("/test/service/service1/srv1".toPath, dependencies = Set("/test/database".toPath))
      val current: Group = Group.empty.copy(groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/database".toPath, groups = Set(
            Group("/test/database/mongo".toPath, Map(mongoApp.id -> mongoApp))
          )),
          Group("/test/service".toPath, groups = Set(
            Group("/test/service/service1".toPath, Map(serviceApp1.id -> serviceApp1))
          ))
        ))))

      Then("the cycle is detected")
      current.hasNonCyclicDependencies should equal(false)
    }

    it("can contain a path which has the same name multiple times in it") {
      Given("a group with subgroups having the same name")
      val serviceApp = AppDefinition("/test/service/test/app".toPath, cmd = Some("Foobar"))
      val reference: Group = Group("/".toPath, groups = Set(
        Group("/test".toPath, groups = Set(
          Group("/test/service".toPath, groups = Set(
            Group("/test/service/test".toPath, Map(serviceApp.id -> serviceApp))
          ))
        ))
      ))

      When("App is updated")
      val app = AppDefinition("/test/service/test/app".toPath, cmd = Some("Foobar"))
      val group = Group(PathId("/"), Map(app.id -> app))
      val updatedGroup = group.updateApp(app.id, { a => app }, Timestamp.zero)
      val ids = updatedGroup.transitiveGroups.map(_.id)

      Then("All non existing subgroups should be created")
      ids should equal(reference.transitiveGroups.map(_.id))
    }

    it("relative dependencies should be resolvable") {
      Given("a group with an app having relative dependency")
      val app1 = AppDefinition("app1".toPath, cmd = Some("foo"))
      val app2 = AppDefinition("app2".toPath, cmd = Some("bar"), dependencies = Set("../app1".toPath))
      val group: Group = Group("/".toPath, groups = Set(
        Group("group".toPath, apps = Map(app1.id -> app1),
          groups = Set(Group("subgroup".toPath, Map(app2.id -> app2))))
      ))

      When("group is validated")
      val result = validate(group)(Group.validRootGroup(maxApps = None))

      Then("result should be a success")
      result.isSuccess should be(true)
    }

    it("Group with app in wrong group is not valid") {
      Given("Group with nested app of wrong path")
      val app = AppDefinition(PathId("/root"), cmd = Some("test"))
      val invalid = Group(PathId.empty, groups = Set(
        Group(PathId("nested"), apps = Map(app.id -> app))
      ))

      When("group is validated")
      val invalidResult = validate(invalid)(Group.validRootGroup(maxApps = None))

      Then("validation is not successful")
      invalidResult.isSuccess should be(false)
    }

    it("Group with group in wrong group is not valid") {
      Given("Group with nested app of wrong path")
      val invalid = Group(PathId.empty, groups = Set(
        Group(PathId("nested"), groups = Set(
          Group(PathId("/root"))
        ))
      ))

      When("group is validated")
      val invalidResult = validate(invalid)(Group.validRootGroup(maxApps = None))

      Then("validation is not successful")
      invalidResult.isSuccess should be(false)
    }

    it("Group with app in correct group is valid") {
      Given("Group with nested app of wrong path")
      val app = AppDefinition(PathId("/nested/foo"), cmd = Some("test"))
      val valid = Group(PathId.empty, groups = Set(
        Group(PathId("nested"), apps = Map(app.id -> app))
      ))

      When("group is validated")
      val validResult = validate(valid)(Group.validRootGroup(maxApps = None))

      Then("validation is successful")
      validResult.isSuccess should be(true)
    }
  }
}
