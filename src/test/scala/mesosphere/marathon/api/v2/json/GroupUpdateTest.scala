package mesosphere.marathon
package api.v2.json

import com.wix.accord.validate
import mesosphere.UnitTest
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, PathId, RootGroup, Timestamp }
import mesosphere.marathon.test.GroupCreation

class GroupUpdateTest extends UnitTest with GroupCreation {
  "GroupUpdate" should {
    "A group update can be applied to an empty group" in {
      Given("An empty group with updates")
      val rootGroup = createRootGroup()
      val update = GroupUpdate(PathId.empty, Set.empty[AppDefinition], Set(
        GroupUpdate("test".toPath, Set.empty[AppDefinition], Set(
          GroupUpdate.empty("foo".toPath)
        )),
        GroupUpdate(
          "apps".toPath,
          Set(AppDefinition("app1".toPath, Some("foo"),
            dependencies = Set("d1".toPath, "../test/foo".toPath, "/test".toPath)))
        )
      )
      )
      val timestamp = Timestamp.now()

      When("The update is performed")
      val result = RootGroup.fromGroup(update(rootGroup, timestamp))

      validate(result)(RootGroup.valid(Set())).isSuccess should be(true)

      Then("The update is applied correctly")
      result.id should be(PathId.empty)
      result.groupsById should have size 2
      val test = result.group("test".toRootPath)
      test should be('defined)
      test.get.groupsById should have size 1
      val apps = result.group("apps".toRootPath)
      apps should be('defined)
      apps.get.apps should have size 1
      val app = apps.get.apps.head
      app._1.toString should be ("/apps/app1")
      app._2.dependencies should be (Set("/apps/d1".toPath, "/test/foo".toPath, "/test".toPath))
    }

    "A group update can be applied to existing entries" in {
      Given("A group with updates of existing nodes")
      val blaApp = AppDefinition("/test/bla".toPath, Some("foo"))
      val actual = createRootGroup(groups = Set(
        createGroup("/test".toPath, apps = Map(blaApp.id -> blaApp)),
        createGroup("/apps".toPath, groups = Set(createGroup("/apps/foo".toPath)))
      ))
      val update = GroupUpdate(
        PathId.empty,
        Set.empty[AppDefinition],
        Set(
          GroupUpdate(
            "test".toPath,
            Set.empty[AppDefinition],
            Set(GroupUpdate.empty("foo".toPath))
          ),
          GroupUpdate(
            "apps".toPath,
            Set(AppDefinition("app1".toPath, Some("foo"),
              dependencies = Set("d1".toPath, "../test/foo".toPath, "/test".toPath)))
          )
        )
      )
      val timestamp = Timestamp.now()

      When("The update is performed")
      val result: RootGroup = RootGroup.fromGroup(update(actual, timestamp))

      validate(result)(RootGroup.valid(Set())).isSuccess should be(true)

      Then("The update is applied correctly")
      result.id should be(PathId.empty)
      result.groupsById should have size 2
      val test = result.group("test".toRootPath)
      test should be('defined)
      test.get.groupsById should have size 1
      test.get.apps should have size 1
      val apps = result.group("apps".toRootPath)
      apps should be('defined)
      apps.get.groupsById should have size 1
      apps.get.apps should have size 1
      val app = apps.get.apps.head
      app._1.toString should be ("/apps/app1")
      app._2.dependencies should be (Set("/apps/d1".toPath, "/test/foo".toPath, "/test".toPath))
    }

    "GroupUpdate will update a Group correctly" in {
      Given("An existing group with two subgroups")
      val app1 = AppDefinition("/test/group1/app1".toPath, Some("foo"))
      val app2 = AppDefinition("/test/group2/app2".toPath, Some("foo"))
      val current = createGroup(
        "/test".toPath,
        groups = Set(
          createGroup("/test/group1".toPath, Map(app1.id -> app1)),
          createGroup("/test/group2".toPath, Map(app2.id -> app2))
        )
      )

      When("A group update is applied")
      val update = GroupUpdate(
        "/test".toPath,
        Set.empty[AppDefinition],
        Set(
          GroupUpdate("/test/group1".toPath, Set(AppDefinition("/test/group1/app3".toPath, Some("foo")))),
          GroupUpdate(
            "/test/group3".toPath,
            Set.empty[AppDefinition],
            Set(GroupUpdate("/test/group3/sub1".toPath, Set(AppDefinition(
              "/test/group3/sub1/app4".toPath,
              Some("foo")))))
          )
        )
      )

      val timestamp = Timestamp.now()
      val next = update(current, timestamp)
      val result = createRootGroup(groups = Set(next))

      validate(result)(RootGroup.valid(Set())).isSuccess should be(true)

      Then("The update is reflected in the current group")
      result.id.toString should be("/")
      result.apps should be('empty)
      val group0 = result.group("/test".toPath).get
      group0.id.toString should be("/test")
      group0.apps should be('empty)
      group0.groupsById should have size 2
      val group1 = result.group("/test/group1".toPath).get
      group1.id should be("/test/group1".toPath)
      group1.apps.head._1 should be("/test/group1/app3".toPath)
      val group3 = result.group("/test/group3".toPath).get
      group3.id should be("/test/group3".toPath)
      group3.apps should be('empty)
    }

    "A group update should not contain a version" in {
      val update = GroupUpdate(None, version = Some(Timestamp.now()))
      intercept[IllegalArgumentException] {
        update(createRootGroup(), Timestamp.now())
      }
    }

    "A group update should not contain a scaleBy" in {
      val update = GroupUpdate(None, scaleBy = Some(3))
      intercept[IllegalArgumentException] {
        update(createRootGroup(), Timestamp.now())
      }
    }

    "Relative path of a dependency, should be relative to group and not to the app" in {
      Given("A group with two apps. Second app is dependend of first.")
      val update = GroupUpdate(PathId.empty, Set.empty[AppDefinition], Set(
        GroupUpdate(
          "test-group".toPath,
          Set(
            AppDefinition("test-app1".toPath, Some("foo")),
            AppDefinition("test-app2".toPath, Some("foo"), dependencies = Set("test-app1".toPath)))
        )
      ))

      When("The update is performed")
      val result = RootGroup.fromGroup(update(createRootGroup(), Timestamp.now()))

      validate(result)(RootGroup.valid(Set())).isSuccess should be(true)

      Then("The update is applied correctly")
      val group = result.group("test-group".toRootPath)
      group should be('defined)
      group.get.apps should have size 2
      val dependentApp = group.get.app("/test-group/test-app2".toPath).get
      dependentApp.dependencies should be (Set("/test-group/test-app1".toPath))
    }
  }
}
