package mesosphere.marathon
package api.v2.json

import com.wix.accord.validate
import mesosphere.UnitTest
import mesosphere.marathon.api.v2.{AppNormalization, GroupNormalization}
import mesosphere.marathon.raml.{App, GroupConversion, GroupUpdate, Raml}
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._

class GroupUpdateTest extends UnitTest {
  val noEnabledFeatures = AllConf.withTestConfig()
  val appConversionFunc: (App => AppDefinition) = { app =>
    // assume canonical form and that the app is valid
    Raml.fromRaml(
      AppNormalization.apply(AppNormalization.Configuration(None, "bridge-name", Set(), ResourceRole.Unreserved)).normalized(app)
    )
  }

  "GroupUpdate" should {
    "A group update can be applied to an empty group" in {
      Given("An empty group with updates")
      val rootGroup = Builders.newRootGroup()
      val update = GroupUpdate(
        Some(PathId.root.toString),
        Some(Set.empty[App]),
        Some(
          Set(
            GroupUpdate(Some("test"), Some(Set.empty[App]), Some(Set(Group.emptyUpdate("foo".toPath)))),
            GroupUpdate(Some("apps"), Some(Set(App("app1", cmd = Some("foo"), dependencies = Set("d1", "../test/foo", "/test")))))
          )
        )
      )
      val timestamp = Timestamp.now()

      When("The update is performed")
      val normalized = GroupNormalization(noEnabledFeatures, rootGroup).updateNormalization(PathId.root).normalized(update)
      val result: Group = GroupConversion(normalized, rootGroup, timestamp).apply(appConversionFunc)

      validate(rootGroup.updatedWith(result))(RootGroup.validRootGroup(noEnabledFeatures)).isSuccess should be(true)

      Then("The update is applied correctly")
      result.id should be(PathId.root)
      result.groupsById should have size 2
      val test = result.group("test".toAbsolutePath)
      test should be('defined)
      test.get.groupsById should have size 1
      val apps = result.group("apps".toAbsolutePath)
      apps should be('defined)
      apps.get.apps should have size 1
      val app = apps.get.apps.head
      app._1.toString should be("/apps/app1")
      app._2.dependencies should be(Set(AbsolutePathId("/apps/d1"), AbsolutePathId("/test/foo"), AbsolutePathId("/test")))
    }

    "A group update can be applied to existing entries" in {
      Given("A group with updates of existing nodes")
      val blaApp = Builders.newAppDefinition.command(AbsolutePathId("/test/bla"))
      val initialRootGroup = Builders.newRootGroup(apps = Seq(blaApp), groupIds = Seq("/apps/foo".toAbsolutePath))
      val update = GroupUpdate(
        Some(PathId.root.toString),
        Some(Set.empty[App]),
        Some(
          Set(
            GroupUpdate(
              Some("test"),
              None,
              Some(Set(Group.emptyUpdate("foo".toPath)))
            ),
            GroupUpdate(
              Some("apps"),
              Some(Set(App("app1", cmd = Some("foo"), dependencies = Set("d1", "../test/foo", "/test"))))
            )
          )
        )
      )
      val timestamp = Timestamp.now()

      When("The update is performed")
      val normalized = GroupNormalization(noEnabledFeatures, initialRootGroup).updateNormalization(PathId.root).normalized(update)
      val result: RootGroup =
        initialRootGroup.updatedWith(
          GroupConversion(normalized, initialRootGroup, timestamp).apply(appConversionFunc)
        )

      validate(result)(RootGroup.validRootGroup(noEnabledFeatures)).isSuccess should be(true)

      Then("The update is applied correctly")
      result.id should be(PathId.root)
      result.groupsById should have size 2
      val test = result.group("test".toAbsolutePath)
      test should be('defined)
      test.get.groupsById should have size 1
      test.get.apps should have size 1
      val apps = result.group("apps".toAbsolutePath)
      apps should be('defined)
      apps.get.groupsById should have size 1
      apps.get.apps should have size 1
      val app = apps.get.apps.head
      app._1.toString should be("/apps/app1")
      app._2.dependencies should be(Set(AbsolutePathId("/apps/d1"), AbsolutePathId("/test/foo"), AbsolutePathId("/test")))
    }

    "GroupUpdate will update a Group correctly" in {
      Given("An existing group with two subgroups")
      val app1 = AppDefinition(AbsolutePathId("/test/group1/app1"), Some("foo"), role = "*")
      val app2 = AppDefinition(AbsolutePathId("/test/group2/app2"), Some("foo"), role = "*")
      val current = Builders.newRootGroup(apps = Seq(app1, app2)).groupsById("/test".toAbsolutePath)

      When("A group update is applied")
      val update = GroupUpdate(
        Some("/test"),
        Some(Set.empty[App]),
        Some(
          Set(
            GroupUpdate(Some("/test/group1"), Some(Set(App("/test/group1/app3", cmd = Some("foo"))))),
            GroupUpdate(
              Some("/test/group3"),
              Some(Set.empty[App]),
              Some(Set(GroupUpdate(Some("/test/group3/sub1"), Some(Set(App("/test/group3/sub1/app4", cmd = Some("foo")))))))
            )
          )
        )
      )

      val timestamp = Timestamp.now()
      val normalized =
        GroupNormalization(noEnabledFeatures, Builders.newRootGroup()).updateNormalization(AbsolutePathId("/test")).normalized(update)
      val next = GroupConversion(normalized, current, timestamp).apply(appConversionFunc)
      val result = Builders.newRootGroup(groups = Seq(next))

      validate(result)(RootGroup.validRootGroup(noEnabledFeatures)).isSuccess should be(true)

      Then("The update is reflected in the current group")
      result.id.toString should be("/")
      result.apps should be('empty)
      val group0 = result.group("/test".toAbsolutePath).get
      group0.id.toString should be("/test")
      group0.apps should be('empty)
      group0.groupsById should have size 2
      val group1 = result.group("/test/group1".toAbsolutePath).get
      group1.id should be("/test/group1".toAbsolutePath)
      group1.apps.head._1 should be(AbsolutePathId("/test/group1/app3"))
      val group3 = result.group("/test/group3".toAbsolutePath).get
      group3.id should be(AbsolutePathId("/test/group3"))
      group3.apps should be('empty)
    }

    "A group update should not contain a version" in {
      val update = GroupUpdate(None, version = Some(Timestamp.now().toOffsetDateTime))
      intercept[IllegalArgumentException] {
        GroupConversion(update, Builders.newRootGroup(), Timestamp.now()).apply(appConversionFunc)
      }
    }

    "A group update should not contain a scaleBy" in {
      val update = GroupUpdate(None, scaleBy = Some(3))
      intercept[IllegalArgumentException] {
        GroupConversion(update, Builders.newRootGroup(), Timestamp.now()).apply(appConversionFunc)
      }
    }

    "Relative path of a dependency, should be relative to group and not to the app" in {
      Given("A group with two apps. Second app is dependent of first.")
      val update = GroupUpdate(
        Some(PathId.root.toString),
        Some(Set.empty[App]),
        Some(
          Set(
            GroupUpdate(
              Some("test-group"),
              Some(Set(App("test-app1", cmd = Some("foo")), App("test-app2", cmd = Some("foo"), dependencies = Set("test-app1"))))
            )
          )
        )
      )

      When("The update is performed")
      val normalized = GroupNormalization(noEnabledFeatures, Builders.newRootGroup()).updateNormalization(PathId.root).normalized(update)
      val initialRootGroup = Builders.newRootGroup()
      val result = GroupConversion(normalized, initialRootGroup, Timestamp.now()).apply(appConversionFunc)

      validate(initialRootGroup.updatedWith(result))(RootGroup.validRootGroup(noEnabledFeatures)).isSuccess should be(true)

      Then("The update is applied correctly")
      val group = result.group("test-group".toAbsolutePath)
      group should be('defined)
      group.get.apps should have size 2
      val dependentApp = group.get.app("/test-group/test-app2".toAbsolutePath).get
      dependentApp.dependencies should be(Set(AbsolutePathId("/test-group/test-app1")))
    }
  }
}
