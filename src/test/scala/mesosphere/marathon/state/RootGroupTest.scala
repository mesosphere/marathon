package mesosphere.marathon
package state

import com.wix.accord._
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.core.pod.{ MesosContainer, PodDefinition }
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.stream._
import mesosphere.marathon.test.GroupCreation
import org.scalatest.{ FunSpec, GivenWhenThen, Matchers }

class RootGroupTest extends FunSpec with GivenWhenThen with Matchers with GroupCreation {

  describe("A Group") {

    it("can find a group by its path") {
      Given("an existing group with two subgroups")
      val app1 = AppDefinition("/test/group1/app1".toPath)
      val app2 = AppDefinition("/test/group2/app2".toPath)
      val current = createRootGroup(
        groups = Set(
          createGroup("/test".toPath, groups = Set(
            createGroup("/test/group1".toPath, Map(app1.id -> app1)),
            createGroup("/test/group2".toPath, Map(app2.id -> app2))
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
      val current = createRootGroup(
        groups = Set(
          createGroup("/test".toPath, groups = Set(
            createGroup("/test/group1".toPath, Map(app1.id -> app1)),
            createGroup("/test/group2".toPath, Map(app2.id -> app2))
          ))))

      When("a group with a specific path is requested")
      val path = PathId("/test/unknown")

      Then("the group is not found")
      current.group(path) should be('empty)
    }

    it("can delete a node based in the path") {
      Given("an existing group with two subgroups")
      val current = createRootGroup().makeGroup("/test/foo/one".toPath).makeGroup("/test/bla/two".toPath)

      When("a node will be deleted based on path")
      val rootGroup = current.removeGroup("/test/foo".toPath)

      Then("the update has been applied")
      rootGroup.group("/test/foo".toPath) should be('empty)
      rootGroup.group("/test/bla".toPath) should be('defined)
    }

    it("can make groups specified by a path") {
      Given("a group with subgroups")
      val app1 = AppDefinition("/test/group1/app1".toPath)
      val app2 = AppDefinition("/test/group2/app2".toPath)
      val current = createRootGroup(
        groups = Set(
          createGroup("/test".toPath, groups = Set(
            createGroup("/test/group1".toPath, Map(app1.id -> app1)),
            createGroup("/test/group2".toPath, Map(app2.id -> app2))
          ))))

      When("a non existing path is requested")
      val path = PathId("/test/group3/group4/group5")
      val rootGroup = current.makeGroup(path)

      Then("the path has been created")
      rootGroup.group(path) should be('defined)

      When("a partly existing path is requested")
      val path2 = PathId("/test/group1/group4/group5")
      val rootGroup2 = current.makeGroup(path2)

      Then("only the missing path has been created")
      rootGroup2.group(path2) should be('defined)

      When("the path is already existent")
      val path3 = PathId("/test/group1")
      val rootGroup3 = current.makeGroup(path3)

      Then("nothing has been changed")
      rootGroup3 should equal(current)
    }

    it("can replace a group without apps by an app definition") {
      // See https://github.com/mesosphere/marathon/issues/851
      // Groups are created implicitly by creating apps and are not visible as separate entities
      // at the time of the creation of this test/issue. They are only visible in the GUI if they contain apps.

      Given("an existing group /some/nested which does not directly or indirectly contain apps")
      val current =
        createRootGroup()
          .makeGroup("/some/nested/path".toPath)
          .makeGroup("/some/nested/path2".toPath)

      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))

      When("requesting to put an app definition")
      val changed = current.updateApp(
        "/some/nested".toPath,
        _ => AppDefinition("/some/nested".toPath, cmd = Some("true")),
        Timestamp.now())

      Then("the group with same path has been replaced by the new app definition")
      changed.transitiveGroupsById.keys.map(_.toString) should be(Set("/", "/some"))
      changed.transitiveAppsById.keys.map(_.toString) should be(Set("/some/nested"))

      Then("the resulting group should be valid when represented in the V2 API model")
      validate(changed)(RootGroup.valid(Set())) should be (Success)
    }

    it("cannot replace a group with apps by an app definition") {
      Given("an existing group /some/nested which does contain an app")
      val current =
        createRootGroup()
          .makeGroup("/some/nested/path".toPath)
          .makeGroup("/some/nested/path2".toPath)
          .updateApp(
            "/some/nested/path2/app".toPath,
            _ => AppDefinition("/some/nested/path2/app".toPath, cmd = Some("true")),
            Timestamp.now())

      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))

      When("requesting to put an app definition")
      val changed = current.updateApp(
        "/some/nested".toPath,
        _ => AppDefinition("/some/nested".toPath, cmd = Some("true")),
        Timestamp.now())

      Then("the group with same path has NOT been replaced by the new app definition")
      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))
      changed.transitiveAppIds.map(_.toString) should be(Set("/some/nested", "/some/nested/path2/app"))

      Then("the conflict will be detected by our V2 API model validation")
      val result = validate(changed)(RootGroup.valid(Set()))
      result.isFailure should be(true)
      ValidationHelper.getAllRuleConstrains(result).head
        .message should be ("Groups and Applications may not have the same identifier.")
    }

    it("cannot replace a group with pods by an app definition") {
      Given("an existing group /some/nested which does contain an pod")
      val current =
        createRootGroup()
          .makeGroup("/some/nested/path".toPath)
          .makeGroup("/some/nested/path2".toPath)
          .updatePod(
            "/some/nested/path2/pod".toPath,
            _ => PodDefinition(id = PathId("/some/nested/path2/pod")),
            Timestamp.now())

      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))

      When("requesting to put an app definition")
      val changed = current.updateApp(
        "/some/nested".toPath,
        _ => AppDefinition("/some/nested".toPath, cmd = Some("true")),
        Timestamp.now())

      Then("the group with same path has NOT been replaced by the new app definition")
      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))
      changed.transitiveAppIds.map(_.toString) should be(Set("/some/nested"))
      changed.transitivePodsById.keySet.map(_.toString) should be(Set("/some/nested/path2/pod"))

      Then("the conflict will be detected by our V2 API model validation")
      val result = validate(changed)(RootGroup.valid(Set()))
      result.isFailure should be(true)
      ValidationHelper.getAllRuleConstrains(result).head
        .message should be ("Groups and Applications may not have the same identifier.")
    }

    it("cannot replace a group with pods by an pod definition") {
      Given("an existing group /some/nested which does contain an pod")
      val current =
        createRootGroup()
          .makeGroup("/some/nested/path".toPath)
          .makeGroup("/some/nested/path2".toPath)
          .updatePod(
            "/some/nested/path2/pod".toPath,
            _ => PodDefinition(id = PathId("/some/nested/path2/pod")),
            Timestamp.now())

      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))

      When("requesting to put a pod definition")
      val changed = current.updatePod(
        "/some/nested".toPath,
        _ => PodDefinition(
          id = "/some/nested".toPath,
          containers = Seq(MesosContainer(name = "foo", resources = Resources()))),
        Timestamp.now())

      Then("the group with same path has NOT been replaced by the new pod definition")
      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))
      changed.transitiveAppIds.map(_.toString) should be(Set.empty[String])
      changed.transitivePodsById.keySet.map(_.toString) should be(Set("/some/nested", "/some/nested/path2/pod"))

      Then("the conflict will be detected by our V2 API model validation")
      val result = validate(changed)(RootGroup.valid(Set()))
      result.isFailure should be(true)
      ValidationHelper.getAllRuleConstrains(result).head
        .message should be ("Groups and Pods may not have the same identifier.")
    }

    it("can marshal and unmarshal from to protos") {
      Given("a group with subgroups")
      val now = Timestamp(11)
      val fullVersion = VersionInfo.forNewConfig(now)
      val app1 = AppDefinition("/test/group1/app1".toPath, args = Seq("a", "b", "c"), versionInfo = fullVersion)
      val app2 = AppDefinition("/test/group2/app2".toPath, args = Seq("a", "b"), versionInfo = fullVersion)
      val current = createRootGroup(
        groups = Set(
          createGroup("/test".toPath, groups = Set(
            createGroup("/test/group1".toPath, Map(app1.id -> app1)),
            createGroup("/test/group2".toPath, Map(app2.id -> app2))
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
      val current: RootGroup = createRootGroup(
        groups = Set(
          createGroup("/test".toPath, groups = Set(
            createGroup("/test/database".toPath, groups = Set(
              createGroup("/test/database/redis".toPath, Map(redisApp.id -> redisApp)),
              createGroup("/test/database/memcache".toPath, Map(memcacheApp.id -> memcacheApp), dependencies = Set("/test/database/mongo".toPath, "/test/database/redis".toPath)),
              createGroup("/test/database/mongo".toPath, Map(mongoApp.id -> mongoApp), dependencies = Set("/test/database/redis".toPath))
            )),
            createGroup("/test/service".toPath, groups = Set(
              createGroup("/test/service/service1".toPath, Map(serviceApp1.id -> serviceApp1), dependencies = Set("/test/database/memcache".toPath)),
              createGroup("/test/service/service2".toPath, Map(serviceApp2.id -> serviceApp2), dependencies = Set("/test/database".toPath, "/test/service/service1".toPath))
            )),
            createGroup("/test/frontend".toPath, groups = Set(
              createGroup("/test/frontend/app1".toPath, Map(frontendApp1.id -> frontendApp1), dependencies = Set("/test/service/service2".toPath)),
              createGroup("/test/frontend/app2".toPath, Map(frontendApp2.id -> frontendApp2), dependencies = Set("/test/service".toPath, "/test/database/mongo".toPath, "/test/frontend/app1".toPath))
            )),
            createGroup("/test/cache".toPath, groups = Set(
              createGroup("/test/cache/c1".toPath, Map(cacheApp.id -> cacheApp)) //has no dependencies
            ))
          ))))
      current.hasNonCyclicDependencies should equal(true)

      When("the dependency graph is computed")
      val dependencyGraph = current.dependencyGraph
      val ids: Set[PathId] = dependencyGraph.vertexSet.map(_.id)

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

      current.runSpecsWithNoDependencies should have size 2
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
      val current: RootGroup = createRootGroup(
        groups = Set(
          createGroup("/test".toPath, groups = Set(
            createGroup("/test/database".toPath, Map(
              redisApp.id -> redisApp,
              memcacheApp.id -> memcacheApp,
              mongoApp.id -> mongoApp
            )),
            createGroup("/test/service".toPath, Map(
              serviceApp1.id -> serviceApp1,
              serviceApp2.id -> serviceApp2
            )),
            createGroup("/test/frontend".toPath, Map(
              frontendApp1.id -> frontendApp1,
              frontendApp2.id -> frontendApp2
            )),
            createGroup("/test/cache".toPath, Map(cacheApp.id -> cacheApp))))
        ))
      current.hasNonCyclicDependencies should equal(true)

      When("the dependency graph is calculated")
      val dependencyGraph = current.dependencyGraph
      val ids: Set[PathId] = dependencyGraph.vertexSet.map(_.id)

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

      current.runSpecsWithNoDependencies should have size 2
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
      val current: RootGroup = createRootGroup(
        groups = Set(
          createGroup("/test".toPath, groups = Set(
            createGroup("/test/database".toPath, groups = Set(
              createGroup("/test/database/redis".toPath, Map(redisApp.id -> redisApp)),
              createGroup("/test/database/memcache".toPath, Map(memcacheApp.id -> memcacheApp)),
              createGroup("/test/database/mongo".toPath, Map(mongoApp.id -> mongoApp))
            )),
            createGroup("/test/service".toPath, groups = Set(
              createGroup("/test/service/service1".toPath, Map(serviceApp1.id -> serviceApp1)),
              createGroup("/test/service/service2".toPath, Map(serviceApp2.id -> serviceApp2))
            )),
            createGroup("/test/frontend".toPath, groups = Set(
              createGroup("/test/frontend/app1".toPath, Map(frontendApp1.id -> frontendApp1)),
              createGroup("/test/frontend/app2".toPath, Map(frontendApp2.id -> frontendApp2))
            )),
            createGroup("/test/cache".toPath, groups = Set(
              createGroup("/test/cache/c1".toPath, Map(cacheApp1.id -> cacheApp1))
            ))
          ))))
      current.hasNonCyclicDependencies should equal(true)

      When("the dependency graph is calculated")
      val dependencyGraph = current.dependencyGraph

      Then("the dependency graph is correct")
      current.runSpecsWithNoDependencies should have size 8
    }

    it("detects a cyclic dependency graph") {
      Given("a group with cyclic dependencies")
      val mongoApp = AppDefinition("/test/database/mongo/m1".toPath, dependencies = Set("/test/service".toPath))
      val serviceApp1 = AppDefinition("/test/service/service1/srv1".toPath, dependencies = Set("/test/database".toPath))
      val current: RootGroup = createRootGroup(
        groups = Set(
          createGroup("/test".toPath, groups = Set(
            createGroup("/test/database".toPath, groups = Set(
              createGroup("/test/database/mongo".toPath, Map(mongoApp.id -> mongoApp))
            )),
            createGroup("/test/service".toPath, groups = Set(
              createGroup("/test/service/service1".toPath, Map(serviceApp1.id -> serviceApp1))
            ))
          ))))

      Then("the cycle is detected")
      current.hasNonCyclicDependencies should equal(false)
    }

    it("can contain a path which has the same name multiple times in it") {
      Given("a group with subgroups having the same name")
      val serviceApp = AppDefinition("/test/service/test/app".toPath, cmd = Some("Foobar"))
      val reference: Group = createRootGroup(groups = Set(
        createGroup("/test".toPath, groups = Set(
          createGroup("/test/service".toPath, groups = Set(
            createGroup("/test/service/test".toPath, Map(serviceApp.id -> serviceApp))
          ))
        ))
      ))

      When("App is updated")
      val app = AppDefinition("/test/service/test/app".toPath, cmd = Some("Foobar"))
      val rootGroup = createRootGroup(Map(app.id -> app))
      val updatedGroup = rootGroup.updateApp(app.id, { a => app }, Timestamp.zero)
      val ids = updatedGroup.transitiveGroupsById.keys

      Then("All non existing subgroups should be created")
      ids should equal(reference.transitiveGroupsById.keys)
    }

    it("relative dependencies should be resolvable") {
      Given("a group with an app having relative dependency")
      val app1 = AppDefinition("app1".toPath, cmd = Some("foo"))
      val app2 = AppDefinition("app2".toPath, cmd = Some("bar"), dependencies = Set("../app1".toPath))
      val rootGroup = createRootGroup(groups = Set(
        createGroup("group".toPath, apps = Map(app1.id -> app1),
          groups = Set(createGroup("subgroup".toPath, Map(app2.id -> app2))))
      ))

      When("group is validated")
      val result = validate(rootGroup)(RootGroup.valid(Set()))

      Then("result should be a success")
      result.isSuccess should be(true)
    }

    it("Group with app in wrong group is not valid") {
      Given("Group with nested app of wrong path")
      val app = AppDefinition(PathId("/root"), cmd = Some("test"))
      val invalid = createRootGroup(groups = Set(
        createGroup(PathId("nested"), apps = Map(app.id -> app))
      ))

      When("group is validated")
      val invalidResult = validate(invalid)(RootGroup.valid(Set()))

      Then("validation is not successful")
      invalidResult.isSuccess should be(false)
    }

    it("Group with group in wrong group is not valid") {
      Given("Group with nested app of wrong path")
      val invalid = createRootGroup(groups = Set(
        createGroup(PathId("nested"), groups = Set(
          createGroup(PathId("/root"))
        ))
      ))

      When("group is validated")
      val invalidResult = validate(invalid)(RootGroup.valid(Set()))

      Then("validation is not successful")
      invalidResult.isSuccess should be(false)
    }

    it("Root Group with app in wrong group is not valid (Regression for #4901)") {
      Given("Group with nested app of wrong path")
      val app = AppDefinition(PathId("/foo/bla"), cmd = Some("test"))
      val invalid = createRootGroup(apps = Map(app.id -> app))

      When("group is validated")
      val invalidResult = validate(invalid)(RootGroup.valid(Set()))

      Then("validation is not successful")
      invalidResult.isSuccess should be(false)
    }

    it("Group with app in correct group is valid") {
      Given("Group with nested app of wrong path")
      val app = AppDefinition(PathId("/nested/foo"), cmd = Some("test"))
      val valid = createRootGroup(groups = Set(
        createGroup(PathId("nested"), apps = Map(app.id -> app))
      ))

      When("group is validated")
      val validResult = validate(valid)(RootGroup.valid(Set()))

      Then("validation is successful")
      validResult.isSuccess should be(true)
    }

    it("can be written to proto and read back again") {
      Given("A group with subgroups, apps and pods")
      val app = AppDefinition(PathId("/app"))
      val pod = PodDefinition(PathId("/pod"))
      val subGroup = Group(PathId("/group"), transitiveAppsById = Map.empty, transitivePodsById = Map.empty)
      val group = Group(
        id = PathId.empty,
        apps = Map(app.id -> app),
        pods = Map(pod.id -> pod),
        groupsById = Map(subGroup.id -> subGroup),
        transitiveAppsById = Map(app.id -> app),
        transitivePodsById = Map(pod.id -> pod)
      )
      val rootGroup = RootGroup(
        apps = Map(app.id -> app),
        pods = Map(pod.id -> pod),
        groupsById = Map(subGroup.id -> subGroup)
      )

      When("Round trip toProto -> fromProto")
      val protoGroup = Group.fromProto(group.toProto)
      val protoRootGroup = RootGroup.fromProto(rootGroup.toProto)

      Then("The round trip is successful")
      protoGroup should be(group)
      protoRootGroup should be(rootGroup)
    }

    it("properly propagate transitiveAppsById") {
      Given("A non-root group with nested groups")
      val appPath = "/domain/developers/gitlab/git".toPath
      val app = AppDefinition(appPath)

      val groupUpdate = createGroup(
        PathId("/domain/developers"),
        groups = Set(
          createGroup(
            PathId("/domain/developers/gitlab"),
            apps = Map(appPath -> app))))

      val newVersion = Timestamp.now()

      When("updating")
      val updated = RootGroup.empty.putGroup(groupUpdate, newVersion)

      Then("The app path is in the transitiveApps key set ")
      updated.transitiveAppsById.keySet should contain(appPath)
    }

    it("properly propagate transitiveAppsById2") {
      Given("A non-root group with nested groups")
      val appPath = "/domain/developers/gitlab/git".toPath
      val app = AppDefinition(appPath)

      val groupUpdate = createGroup(
        PathId("/domain"),
        groups = Set(createGroup(
          PathId("/domain/developers"),
          groups = Set(
            createGroup(
              PathId("/domain/developers/gitlab"),
              apps = Map(appPath -> app))))))

      val newVersion = Timestamp.now()
      When("updating")
      val updated = RootGroup.empty.putGroup(groupUpdate, newVersion)

      Then("The app path is in the transitiveApps key set ")
      updated.transitiveAppsById.keySet should contain(appPath)
    }

    it("properly propagate transitiveAppsById3") {
      Given("A non-root group without nested groups")
      val appPath = "/domain/developers/gitlab/git".toPath
      val app = AppDefinition(appPath)

      val groupUpdate = createGroup(
        PathId("/domain/developers/gitlab"),
        apps = Map(appPath -> app))

      val newVersion = Timestamp.now()

      When("updating")
      val updated = RootGroup.empty.putGroup(groupUpdate, newVersion)

      Then("The app path is in the transitiveApps key set ")
      updated.transitiveAppsById.keySet should contain(appPath)
    }
  }
}
