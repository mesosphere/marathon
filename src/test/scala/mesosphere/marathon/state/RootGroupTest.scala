package mesosphere.marathon
package state

import com.wix.accord._
import mesosphere.UnitTest
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.core.pod.{MesosContainer, PodDefinition}
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.PathId._
import scala.jdk.CollectionConverters._
import mesosphere.marathon.test.GroupCreation

class RootGroupTest extends UnitTest with GroupCreation {
  val emptyConfig = AllConf.withTestConfig()
  val emptyRootGroup = RootGroup.empty(newGroupStrategy = RootGroup.NewGroupStrategy.fromConfig(NewGroupEnforceRoleBehavior.Off))

  "A Group" should {

    "find an app by its path" in {
      Given("an existing group with two subgroups")
      val app1 = AppDefinition(AbsolutePathId("/test/group1/app1"), role = "*", cmd = Some("sleep"))
      val app2 = AppDefinition(AbsolutePathId("/test/group2/app2"), role = "*", cmd = Some("sleep"))
      val current = createRootGroup(
        groups = Set(
          createGroup("/test".toAbsolutePath, groups = Set(
            createGroup("/test/group1".toAbsolutePath, Map(app1.id -> app1)),
            createGroup("/test/group2".toAbsolutePath, Map(app2.id -> app2))
          ))))

      When("an app with a specific path is requested")
      val path = AbsolutePathId("/test/group1/app1")

      Then("the group is found")
      current.app(path) should be('defined)
    }

    "find an app without a parent" in {
      Given("an existing root group with an app without a parent")
      val app = AppDefinition(AbsolutePathId("/app"), role = "*", cmd = Some("sleep"))
      val current = createRootGroup(apps = Map(app.id -> app))

      When("an app with a specific path is requested")
      val path = AbsolutePathId("/app")

      Then("the group is found")
      current.app(path) should be('defined)
    }

    "cannot find an app if it's not existing" in {
      Given("an existing group with two subgroups")
      val app1 = AppDefinition(AbsolutePathId("/test/group1/app1"), role = "*", cmd = Some("sleep"))
      val app2 = AppDefinition(AbsolutePathId("/test/group2/app2"), role = "*", cmd = Some("sleep"))
      val current = createRootGroup(
        groups = Set(
          createGroup("/test".toAbsolutePath, groups = Set(
            createGroup("/test/group1".toAbsolutePath, Map(app1.id -> app1)),
            createGroup("/test/group2".toAbsolutePath, Map(app2.id -> app2))
          ))))

      When("a group with a specific path is requested")
      val path = AbsolutePathId("/test/group1/unknown")

      Then("the app is not found")
      current.app(path) should be('empty)
    }

    "can find a group by its path" in {
      Given("an existing group with two subgroups")
      val app1 = AppDefinition(AbsolutePathId("/test/group1/app1"), role = "*", cmd = Some("sleep"))
      val app2 = AppDefinition(AbsolutePathId("/test/group2/app2"), role = "*", cmd = Some("sleep"))
      val current = createRootGroup(
        groups = Set(
          createGroup("/test".toAbsolutePath, groups = Set(
            createGroup("/test/group1".toAbsolutePath, Map(app1.id -> app1)),
            createGroup("/test/group2".toAbsolutePath, Map(app2.id -> app2))
          ))))

      When("a group with a specific path is requested")
      val path = AbsolutePathId("/test/group1")

      Then("the group is found")
      current.group(path) should be('defined)
    }

    "can not find a group if its not existing" in {
      Given("an existing group with two subgroups")
      val app1 = AppDefinition(AbsolutePathId("/test/group1/app1"), role = "*", cmd = Some("sleep"))
      val app2 = AppDefinition(AbsolutePathId("/test/group2/app2"), role = "*", cmd = Some("sleep"))
      val current = createRootGroup(
        groups = Set(
          createGroup("/test".toAbsolutePath, groups = Set(
            createGroup("/test/group1".toAbsolutePath, Map(app1.id -> app1)),
            createGroup("/test/group2".toAbsolutePath, Map(app2.id -> app2))
          ))))

      When("a group with a specific path is requested")
      val path = AbsolutePathId("/test/unknown")

      Then("the group is not found")
      current.group(path) should be('empty)
    }

    "can delete a node based in the path" in {
      Given("an existing group with two subgroups")
      val current = createRootGroup().makeGroup("/test/foo/one".toAbsolutePath).makeGroup("/test/bla/two".toAbsolutePath)

      When("a node will be deleted based on path")
      val rootGroup = current.removeGroup("/test/foo".toAbsolutePath)

      Then("the update has been applied")
      rootGroup.group("/test/foo".toAbsolutePath) should be('empty)
      rootGroup.group("/test/bla".toAbsolutePath) should be('defined)
    }

    "can make groups specified by a path" in {
      Given("a group with subgroups")
      val app1 = AppDefinition(AbsolutePathId("/test/group1/app1"), role = "*", cmd = Some("sleep"))
      val app2 = AppDefinition(AbsolutePathId("/test/group2/app2"), role = "*", cmd = Some("sleep"))
      val current = createRootGroup(
        groups = Set(
          createGroup("/test".toAbsolutePath, groups = Set(
            createGroup("/test/group1".toAbsolutePath, Map(app1.id -> app1)),
            createGroup("/test/group2".toAbsolutePath, Map(app2.id -> app2))
          ))))

      When("a non existing path is requested")
      val path = AbsolutePathId("/test/group3/group4/group5")
      val rootGroup = current.makeGroup(path)

      Then("the path has been created")
      rootGroup.group(path) should be('defined)

      When("a partly existing path is requested")
      val path2 = AbsolutePathId("/test/group1/group4/group5")
      val rootGroup2 = current.makeGroup(path2)

      Then("only the missing path has been created")
      rootGroup2.group(path2) should be('defined)

      When("the path is already existent")
      val path3 = AbsolutePathId("/test/group1")
      val rootGroup3 = current.makeGroup(path3)

      Then("nothing has been changed")
      rootGroup3 should equal(current)
    }

    "can replace a group without apps by an app definition" in {
      // See https://github.com/mesosphere/marathon/issues/851
      // Groups are created implicitly by creating apps and are not visible as separate entities
      // at the time of the creation of this test/issue. They are only visible in the GUI if they contain apps.

      Given("an existing group /some/nested which does not directly or indirectly contain apps")
      val current =
        createRootGroup()
          .makeGroup("/some/nested/path".toAbsolutePath)
          .makeGroup("/some/nested/path2".toAbsolutePath)

      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))

      When("requesting to put an app definition")
      val changed = current.updateApp(
        AbsolutePathId("/some/nested"),
        _ => AppDefinition(AbsolutePathId("/some/nested"), role = "*", cmd = Some("true")),
        Timestamp.now())

      Then("the group with same path has been replaced by the new app definition")
      changed.transitiveGroupsById.keys.map(_.toString) should be(Set("/", "/some"))
      changed.transitiveAppIds.map(_.toString) should contain theSameElementsAs (Vector("/some/nested"))

      Then("the resulting group should be valid when represented in the V2 API model")
      validate(changed)(RootGroup.validRootGroup(emptyConfig)) should be(Success)
    }

    "cannot replace a group with apps by an app definition" in {
      Given("an existing group /some/nested which does contain an app")
      val current =
        createRootGroup()
          .makeGroup("/some/nested/path".toAbsolutePath)
          .makeGroup("/some/nested/path2".toAbsolutePath)
          .updateApp(
            AbsolutePathId("/some/nested/path2/app"),
            _ => AppDefinition(AbsolutePathId("/some/nested/path2/app"), role = "*", cmd = Some("true")),
            Timestamp.now())

      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))

      When("requesting to put an app definition")
      val changed = current.updateApp(
        AbsolutePathId("/some/nested"),
        _ => AppDefinition(AbsolutePathId("/some/nested"), role = "*", cmd = Some("true")),
        Timestamp.now())

      Then("the group with same path has NOT been replaced by the new app definition")
      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))
      changed.transitiveAppIds.map(_.toString) should contain theSameElementsAs (Vector("/some/nested", "/some/nested/path2/app"))

      Then("the conflict will be detected by our V2 API model validation")
      val result = validate(changed)(RootGroup.validRootGroup(emptyConfig))
      result.isFailure should be(true)
      ValidationHelper.getAllRuleConstraints(result).head
        .constraint should be("Groups and Applications may not have the same identifier.")
    }

    "cannot replace a group with pods by an app definition" in {
      Given("an existing group /some/nested which does contain an pod")
      val current =
        createRootGroup()
          .makeGroup("/some/nested/path".toAbsolutePath)
          .makeGroup("/some/nested/path2".toAbsolutePath)
          .updatePod(
            AbsolutePathId("/some/nested/path2/pod"),
            _ => PodDefinition(id = AbsolutePathId("/some/nested/path2/pod"), role = "*"),
            Timestamp.now())

      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))

      When("requesting to put an app definition")
      val changed = current.updateApp(
        AbsolutePathId("/some/nested"),
        _ => AppDefinition(AbsolutePathId("/some/nested"), role = "*", cmd = Some("true")),
        Timestamp.now())

      Then("the group with same path has NOT been replaced by the new app definition")
      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))
      changed.transitiveAppIds.map(_.toString) should contain theSameElementsAs (Vector("/some/nested"))
      changed.transitivePodIds.map(_.toString) should contain theSameElementsAs (Vector("/some/nested/path2/pod"))

      Then("the conflict will be detected by our V2 API model validation")
      val result = validate(changed)(RootGroup.validRootGroup(emptyConfig))
      result.isFailure should be(true)
      ValidationHelper.getAllRuleConstraints(result).head
        .constraint should be("Groups and Applications may not have the same identifier.")
    }

    "cannot replace a group with pods by an pod definition" in {
      Given("an existing group /some/nested which does contain an pod")
      val current =
        createRootGroup()
          .makeGroup("/some/nested/path".toAbsolutePath)
          .makeGroup("/some/nested/path2".toAbsolutePath)
          .updatePod(
            AbsolutePathId("/some/nested/path2/pod"),
            _ => PodDefinition(id = AbsolutePathId("/some/nested/path2/pod"), role = "*"),
            Timestamp.now())

      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))

      When("requesting to put a pod definition")
      val changed = current.updatePod(
        AbsolutePathId("/some/nested"),
        _ => PodDefinition(
          id = AbsolutePathId("/some/nested"), role = "*",
          containers = Seq(MesosContainer(name = "foo", resources = Resources()))),
        Timestamp.now())

      Then("the group with same path has NOT been replaced by the new pod definition")
      current.transitiveGroupsById.keys.map(_.toString) should be(
        Set("/", "/some", "/some/nested", "/some/nested/path", "/some/nested/path2"))
      changed.transitiveAppIds.map(_.toString) should be('empty)
      changed.transitivePodIds.map(_.toString) should contain theSameElementsAs (Vector("/some/nested", "/some/nested/path2/pod"))

      Then("the conflict will be detected by our V2 API model validation")
      val result = validate(changed)(RootGroup.validRootGroup(emptyConfig))
      result.isFailure should be(true)
      ValidationHelper.getAllRuleConstraints(result).head
        .constraint should be("Groups and Pods may not have the same identifier.")
    }

    "can turn a group with group dependencies into a dependency graph" in {
      Given("a group with subgroups and dependencies")
      val redisApp = AppDefinition(AbsolutePathId("/test/database/redis/r1"), role = "*", cmd = Some("sleep"))
      val memcacheApp = AppDefinition(AbsolutePathId("/test/database/memcache/c1"), role = "*", cmd = Some("sleep"))
      val mongoApp = AppDefinition(AbsolutePathId("/test/database/mongo/m1"), role = "*", cmd = Some("sleep"))
      val serviceApp1 = AppDefinition(AbsolutePathId("/test/service/service1/s1"), role = "*", cmd = Some("sleep"))
      val serviceApp2 = AppDefinition(AbsolutePathId("/test/service/service2/s2"), role = "*", cmd = Some("sleep"))
      val frontendApp1 = AppDefinition(AbsolutePathId("/test/frontend/app1/a1"), role = "*", cmd = Some("sleep"))
      val frontendApp2 = AppDefinition(AbsolutePathId("/test/frontend/app2/a2"), role = "*", cmd = Some("sleep"))
      val cacheApp = AppDefinition(AbsolutePathId("/test/cache/c1/c1"), role = "*", cmd = Some("sleep"))
      val current: RootGroup = createRootGroup(
        groups = Set(
          createGroup("/test".toAbsolutePath, groups = Set(
            createGroup("/test/database".toAbsolutePath, groups = Set(
              createGroup("/test/database/redis".toAbsolutePath, Map(redisApp.id -> redisApp)),
              createGroup("/test/database/memcache".toAbsolutePath, Map(memcacheApp.id -> memcacheApp),
                dependencies = Set("/test/database/mongo".toAbsolutePath, "/test/database/redis".toAbsolutePath)),
              createGroup("/test/database/mongo".toAbsolutePath, Map(mongoApp.id -> mongoApp),
                dependencies = Set("/test/database/redis".toAbsolutePath))
            )),
            createGroup("/test/service".toAbsolutePath, groups = Set(
              createGroup("/test/service/service1".toAbsolutePath, Map(serviceApp1.id -> serviceApp1),
                dependencies = Set("/test/database/memcache".toAbsolutePath)),
              createGroup("/test/service/service2".toAbsolutePath, Map(serviceApp2.id -> serviceApp2),
                dependencies = Set("/test/database".toAbsolutePath, "/test/service/service1".toAbsolutePath))
            )),
            createGroup("/test/frontend".toAbsolutePath, groups = Set(
              createGroup("/test/frontend/app1".toAbsolutePath, Map(frontendApp1.id -> frontendApp1), dependencies = Set("/test/service/service2".toAbsolutePath)),
              createGroup("/test/frontend/app2".toAbsolutePath, Map(frontendApp2.id -> frontendApp2),
                dependencies = Set("/test/service".toAbsolutePath, "/test/database/mongo".toAbsolutePath, "/test/frontend/app1".toAbsolutePath))
            )),
            createGroup("/test/cache".toAbsolutePath, groups = Set(
              createGroup("/test/cache/c1".toAbsolutePath, Map(cacheApp.id -> cacheApp)) //has no dependencies
            ))
          ))))
      current.hasNonCyclicDependencies should equal(true)

      When("the dependency graph is computed")
      val dependencyGraph = current.dependencyGraph
      val ids: Set[PathId] = dependencyGraph.vertexSet.asScala.map(_.id).toSet

      Then("the dependency graph is correct")
      ids should have size 8

      val expectedIds = Set[PathId](
        AbsolutePathId("/test/database/redis/r1"),
        AbsolutePathId("/test/database/mongo/m1"),
        AbsolutePathId("/test/database/memcache/c1"),
        AbsolutePathId("/test/service/service1/s1"),
        AbsolutePathId("/test/service/service2/s2"),
        AbsolutePathId("/test/frontend/app1/a1"),
        AbsolutePathId("/test/frontend/app2/a2"),
        AbsolutePathId("/test/cache/c1/c1")
      )
      ids should equal(expectedIds)

      val actualAppDependencies: List[(String, String)] = current.applicationDependencies.map { case (left, right) => left.id.toString -> right.id.toString }
      val expectedAppDependencies = List(
        "/test/frontend/app2/a2" -> "/test/frontend/app1/a1",
        "/test/frontend/app2/a2" -> "/test/database/mongo/m1",
        "/test/frontend/app2/a2" -> "/test/service/service2/s2",
        "/test/frontend/app2/a2" -> "/test/service/service1/s1",
        "/test/service/service2/s2" -> "/test/service/service1/s1",
        "/test/service/service2/s2" -> "/test/database/mongo/m1",
        "/test/service/service2/s2" -> "/test/database/memcache/c1",
        "/test/service/service2/s2" -> "/test/database/redis/r1",
        "/test/database/memcache/c1" -> "/test/database/redis/r1",
        "/test/database/memcache/c1" -> "/test/database/mongo/m1",
        "/test/service/service1/s1" -> "/test/database/memcache/c1",
        "/test/database/mongo/m1" -> "/test/database/redis/r1",
        "/test/frontend/app1/a1" -> "/test/service/service2/s2"
      )
      actualAppDependencies should contain theSameElementsAs (expectedAppDependencies)

      current.runSpecsWithNoDependencies should have size 2
    }

    "can turn a group with app dependencies into a dependency graph" in {
      Given("a group with subgroups and dependencies")
      val redisApp = AppDefinition(AbsolutePathId("/test/database/redis"), role = "*", cmd = Some("sleep"))
      val memcacheApp = AppDefinition(AbsolutePathId("/test/database/memcache"), role = "*", dependencies = Set(AbsolutePathId("/test/database/mongo"), AbsolutePathId("/test/database/redis")), cmd = Some("sleep"))
      val mongoApp = AppDefinition(AbsolutePathId("/test/database/mongo"), role = "*", dependencies = Set(AbsolutePathId("/test/database/redis")), cmd = Some("sleep"))
      val serviceApp1 = AppDefinition(AbsolutePathId("/test/service/srv1"), role = "*", dependencies = Set(AbsolutePathId("/test/database/memcache")), cmd = Some("sleep"))
      val serviceApp2 = AppDefinition(AbsolutePathId("/test/service/srv2"), role = "*", dependencies = Set(AbsolutePathId("/test/database/mongo"), AbsolutePathId("/test/service/srv1")), cmd = Some("sleep"))
      val frontendApp1 = AppDefinition(AbsolutePathId("/test/frontend/app1"), role = "*", dependencies = Set(AbsolutePathId("/test/service/srv2")), cmd = Some("sleep"))
      val frontendApp2 = AppDefinition(AbsolutePathId("/test/frontend/app2"), role = "*", dependencies = Set(AbsolutePathId("/test/service/srv2"), AbsolutePathId("/test/database/mongo"), AbsolutePathId("/test/frontend/app1")), cmd = Some("sleep"))
      val cacheApp = AppDefinition(AbsolutePathId("/test/cache/cache1"), role = "*", cmd = Some("sleep"))
      //has no dependencies
      val current: RootGroup = createRootGroup(
        groups = Set(
          createGroup("/test".toAbsolutePath, groups = Set(
            createGroup("/test/database".toAbsolutePath, Map(
              redisApp.id -> redisApp,
              memcacheApp.id -> memcacheApp,
              mongoApp.id -> mongoApp
            )),
            createGroup("/test/service".toAbsolutePath, Map(
              serviceApp1.id -> serviceApp1,
              serviceApp2.id -> serviceApp2
            )),
            createGroup("/test/frontend".toAbsolutePath, Map(
              frontendApp1.id -> frontendApp1,
              frontendApp2.id -> frontendApp2
            )),
            createGroup("/test/cache".toAbsolutePath, Map(cacheApp.id -> cacheApp))))
        ))
      current.hasNonCyclicDependencies should equal(true)

      When("the dependency graph is calculated")
      val dependencyGraph = current.dependencyGraph
      val ids: Set[PathId] = dependencyGraph.vertexSet.asScala.map(_.id).toSet

      Then("the dependency graph is correct")
      ids should have size 8
      ids should not contain AbsolutePathId("/test/cache/c1")
      val expected = Set[PathId](
        AbsolutePathId("/test/database/redis"),
        AbsolutePathId("/test/database/mongo"),
        AbsolutePathId("/test/database/memcache"),
        AbsolutePathId("/test/service/srv1"),
        AbsolutePathId("/test/service/srv2"),
        AbsolutePathId("/test/frontend/app1"),
        AbsolutePathId("/test/frontend/app2"),
        AbsolutePathId("/test/cache/cache1")
      )
      ids should be(expected)

      val actualAppDependencies: List[(String, String)] = current.applicationDependencies.map { case (left, right) => left.id.toString -> right.id.toString }
      val expectedAppDependencies = List(
        "/test/frontend/app2" -> "/test/frontend/app1",
        "/test/frontend/app2" -> "/test/database/mongo",
        "/test/frontend/app2" -> "/test/service/srv2",
        "/test/frontend/app1" -> "/test/service/srv2",
        "/test/database/mongo" -> "/test/database/redis",
        "/test/database/memcache" -> "/test/database/redis",
        "/test/database/memcache" -> "/test/database/mongo",
        "/test/service/srv2" -> "/test/service/srv1",
        "/test/service/srv2" -> "/test/database/mongo",
        "/test/service/srv1" -> "/test/database/memcache"
      )
      actualAppDependencies should contain theSameElementsAs (expectedAppDependencies)

      current.runSpecsWithNoDependencies should have size 2
    }

    "can turn a group without dependencies into a dependency graph" in {
      Given("a group with subgroups and dependencies")
      val redisApp = AppDefinition(AbsolutePathId("/test/database/redis/r1"), role = "*", cmd = Some("sleep"))
      val memcacheApp = AppDefinition(AbsolutePathId("/test/database/memcache/m1"), role = "*", cmd = Some("sleep"))
      val mongoApp = AppDefinition(AbsolutePathId("/test/database/mongo/m1"), role = "*", cmd = Some("sleep"))
      val serviceApp1 = AppDefinition(AbsolutePathId("/test/service/service1/srv1"), role = "*", cmd = Some("sleep"))
      val serviceApp2 = AppDefinition(AbsolutePathId("/test/service/service2/srv2"), role = "*", cmd = Some("sleep"))
      val frontendApp1 = AppDefinition(AbsolutePathId("/test/frontend/app1/a1"), role = "*", cmd = Some("sleep"))
      val frontendApp2 = AppDefinition(AbsolutePathId("/test/frontend/app2/a2"), role = "*", cmd = Some("sleep"))
      val cacheApp1 = AppDefinition(AbsolutePathId("/test/cache/c1/cache1"), role = "*", cmd = Some("sleep"))
      val current: RootGroup = createRootGroup(
        groups = Set(
          createGroup("/test".toAbsolutePath, groups = Set(
            createGroup("/test/database".toAbsolutePath, groups = Set(
              createGroup("/test/database/redis".toAbsolutePath, Map(redisApp.id -> redisApp)),
              createGroup("/test/database/memcache".toAbsolutePath, Map(memcacheApp.id -> memcacheApp)),
              createGroup("/test/database/mongo".toAbsolutePath, Map(mongoApp.id -> mongoApp))
            )),
            createGroup("/test/service".toAbsolutePath, groups = Set(
              createGroup("/test/service/service1".toAbsolutePath, Map(serviceApp1.id -> serviceApp1)),
              createGroup("/test/service/service2".toAbsolutePath, Map(serviceApp2.id -> serviceApp2))
            )),
            createGroup("/test/frontend".toAbsolutePath, groups = Set(
              createGroup("/test/frontend/app1".toAbsolutePath, Map(frontendApp1.id -> frontendApp1)),
              createGroup("/test/frontend/app2".toAbsolutePath, Map(frontendApp2.id -> frontendApp2))
            )),
            createGroup("/test/cache".toAbsolutePath, groups = Set(
              createGroup("/test/cache/c1".toAbsolutePath, Map(cacheApp1.id -> cacheApp1))
            ))
          ))))
      current.hasNonCyclicDependencies should equal(true)

      When("the dependency graph is calculated")
      current.dependencyGraph

      Then("the dependency graph is correct")
      current.runSpecsWithNoDependencies should have size 8
    }

    "detects a cyclic dependency graph" in {
      Given("a group with cyclic dependencies")
      val mongoApp = AppDefinition(AbsolutePathId("/test/database/mongo/m1"), role = "*", dependencies = Set(AbsolutePathId("/test/service")), cmd = Some("sleep"))
      val serviceApp1 = AppDefinition(AbsolutePathId("/test/service/service1/srv1"), role = "*", dependencies = Set(AbsolutePathId("/test/database")), cmd = Some("sleep"))
      val current: RootGroup = createRootGroup(
        groups = Set(
          createGroup("/test".toAbsolutePath, groups = Set(
            createGroup("/test/database".toAbsolutePath, groups = Set(
              createGroup("/test/database/mongo".toAbsolutePath, Map(mongoApp.id -> mongoApp), validate = false)
            ), validate = false),
            createGroup("/test/service".toAbsolutePath, groups = Set(
              createGroup("/test/service/service1".toAbsolutePath, Map(serviceApp1.id -> serviceApp1), validate = false)
            ), validate = false)
          ))), validate = false)

      Then("the cycle is detected")
      current.hasNonCyclicDependencies should equal(false)
    }

    "can contain a path which has the same name multiple times in it" in {
      Given("a group with subgroups having the same name")
      val serviceApp = AppDefinition(AbsolutePathId("/test/service/test/app"), role = "*", cmd = Some("Foobar"))
      val reference: Group = createRootGroup(groups = Set(
        createGroup("/test".toAbsolutePath, groups = Set(
          createGroup("/test/service".toAbsolutePath, groups = Set(
            createGroup("/test/service/test".toAbsolutePath, Map(serviceApp.id -> serviceApp))
          ))
        ))
      ))

      When("App is updated")
      val app = AppDefinition(AbsolutePathId("/test/service/test/app"), role = "*", cmd = Some("Foobar"))
      val rootGroup = createRootGroup()
      val updatedGroup = rootGroup.updateApp(app.id, { a => app }, Timestamp.zero)
      val ids = updatedGroup.transitiveGroupsById.keys

      Then("All non existing subgroups should be created")
      ids should equal(reference.transitiveGroupsById.keys)
    }
    // TODO AN: This shouldn't be tested on rootGroup but on GroupsResource or AppResources

    //    "relative dependencies should be resolvable" in {
    //      Given("a group with an app having relative dependency")
    //      val app1 = AppDefinition(AbsolutePathId("/group/app1"), role = "*", cmd = Some("foo"))
    //      val app2 = AppDefinition(AbsolutePathId("/group/subgroup/app2"), role = "*", cmd = Some("bar"), dependencies = Set("../app1".toPath))
    //      val rootGroup = createRootGroup(groups = Set(
    //        createGroup("/group".toAbsolutePath, apps = Map(app1.id -> app1),
    //          groups = Set(createGroup("/group/subgroup".toAbsolutePath, Map(app2.id -> app2))))
    //      ))
    //
    //      When("group is validated")
    //      val result = validate(rootGroup)(RootGroup.validRootGroup(emptyConfig))
    //
    //      Then("result should be a success")
    //      result.isSuccess should be(true)
    //    }

    "Group with app in wrong group is not valid" in {
      Given("Group with nested app of wrong path")
      val app = AppDefinition(AbsolutePathId("/root"), role = "*", cmd = Some("test"))
      val invalid = createRootGroup(groups = Set(
        createGroup(AbsolutePathId("nested"), apps = Map(app.id -> app), validate = false)
      ), validate = false)

      When("group is validated")
      val invalidResult = validate(invalid)(RootGroup.validRootGroup(emptyConfig))

      Then("validation is not successful")
      invalidResult.isSuccess should be(false)
    }

    "Group with group in wrong group is not valid" in {
      Given("Group with nested app of wrong path")
      val invalid = createRootGroup(groups = Set(
        createGroup(AbsolutePathId("nested"), groups = Set(
          createGroup(AbsolutePathId("/root"), validate = false)
        ), validate = false)
      ), validate = false)

      When("group is validated")
      val invalidResult = validate(invalid)(RootGroup.validRootGroup(emptyConfig))

      Then("validation is not successful")
      invalidResult.isSuccess should be(false)
    }

    "Root Group with app in wrong group is not valid (Regression for #4901)" in {
      Given("Group with nested app of wrong path")
      val app = AppDefinition(AbsolutePathId("/foo/bla"), role = "*", cmd = Some("test"))
      val invalid = createRootGroup(apps = Map(app.id -> app), validate = false)

      When("group is validated")
      val invalidResult = validate(invalid)(RootGroup.validRootGroup(emptyConfig))

      Then("validation is not successful")
      invalidResult.isSuccess should be(false)
    }

    "Group with app in correct group is valid" in {
      Given("Group with nested app of wrong path")
      val app = AppDefinition(AbsolutePathId("/nested/foo"), role = "*", cmd = Some("test"))
      val valid = createRootGroup(groups = Set(
        createGroup(AbsolutePathId("/nested"), apps = Map(app.id -> app))
      ))

      When("group is validated")
      val validResult = validate(valid)(RootGroup.validRootGroup(emptyConfig))

      Then("validation is successful")
      validResult.isSuccess should be(true)
    }

    "should receive a non-root Group with nested groups as an updated and properly propagate transitiveAppsById" in {
      val appPath = AbsolutePathId("/domain/developers/gitlab/git")
      val app = AppDefinition(appPath, role = "*", cmd = Some("sleep"))

      val groupUpdate = createGroup(
        AbsolutePathId("/domain/developers"),
        groups = Set(
          createGroup(
            AbsolutePathId("/domain/developers/gitlab"),
            apps = Map(appPath -> app))))

      val newVersion = Timestamp.now()
      val updated = emptyRootGroup.putGroup(groupUpdate, newVersion)
      updated.transitiveAppIds should contain(appPath)
    }

    "should receive a non-root Group with nested groups as an updated and properly propagate transitiveAppsByI2 2" in {
      val appPath = AbsolutePathId("/domain/developers/gitlab/git")
      val app = AppDefinition(appPath, role = "*", cmd = Some("sleep"))

      val groupUpdate = createGroup(
        AbsolutePathId("/domain"),
        groups = Set(createGroup(
          AbsolutePathId("/domain/developers"),
          groups = Set(
            createGroup(
              AbsolutePathId("/domain/developers/gitlab"),
              apps = Map(appPath -> app))))))

      val newVersion = Timestamp.now()
      val updated = emptyRootGroup.putGroup(groupUpdate, newVersion)
      updated.transitiveAppIds should contain(appPath)
    }

    "should receive a non-root Group without nested groups as an updated and properly propagate transitiveAppsById 3" in {
      val appPath = AbsolutePathId("/domain/developers/gitlab/git")
      val app = AppDefinition(appPath, role = "*", cmd = Some("sleep"))

      val groupUpdate = createGroup(
        AbsolutePathId("/domain/developers/gitlab"),
        apps = Map(appPath -> app))

      val newVersion = Timestamp.now()
      val updated = emptyRootGroup.putGroup(groupUpdate, newVersion)
      updated.transitiveAppIds should contain(appPath)
    }

    "update a mid-level Group dependency" in {
      Given("a three level hierarchy")
      val current: RootGroup = createRootGroup(
        groups = Set(
          createGroup("/top".toAbsolutePath, groups = Set(
            createGroup("/top/mid".toAbsolutePath, groups = Set(
              createGroup("/top/mid/end".toAbsolutePath)
            ))
          )),
          createGroup("/side".toAbsolutePath)
        ))

      When("we update the dependency of the mid-level group")
      val updatedRoot = current.updateDependencies("/top/mid".toAbsolutePath, _ => Set("/side".toAbsolutePath))

      Then("the update should be reflected in the new root group")
      updatedRoot.group("/top/mid".toAbsolutePath).value.dependencies should be(Set(AbsolutePathId("/side")))
    }

    "update a top-level group parameter" in {
      Given("a three level hierarchy")
      val current: RootGroup = createRootGroup(
        groups = Set(
          createGroup("/top".toAbsolutePath, groups = Set(
            createGroup("/top/mid".toAbsolutePath, groups = Set(
              createGroup("/top/mid/end".toAbsolutePath)
            ))
          )),
          createGroup("/side".toAbsolutePath)
        ))

      When("we update the enforce role parameter of the top-level group")
      val groupUpdate = current.group("/top".toAbsolutePath).value.withEnforceRole(true)
      val updatedRoot = current.putGroup(groupUpdate)

      Then("the update should be reflected in the new root group")
      updatedRoot.group("/top".toAbsolutePath).value.enforceRole should be(true)
    }
  }
}
