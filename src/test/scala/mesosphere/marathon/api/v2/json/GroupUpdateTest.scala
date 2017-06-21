package mesosphere.marathon
package api.v2.json

import com.wix.accord.validate
import mesosphere.UnitTest
import mesosphere.marathon.api.v2.AppNormalization
import mesosphere.marathon.raml.{ App, GroupConversion, GroupUpdate, Raml }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation

class GroupUpdateTest extends UnitTest with GroupCreation {
  val noEnabledFeatures = Set.empty[String]
  val appConversionFunc: (App => AppDefinition) = { app =>
    // assume canonical form and that the app is valid
    Raml.fromRaml(AppNormalization.apply(AppNormalization.Configuration(None, "bridge-name")).normalized(app))
  }
  implicit val groupUpdateRamlReader = raml.GroupConversion.groupUpdateRamlReads // HACK: workaround bogus compiler error?!

  "GroupUpdate" should {
    "A group update can be applied to an empty group" in {
      Given("An empty group with updates")
      val rootGroup = createRootGroup()
      val update = GroupUpdate(
        Some(PathId.empty.toString),
        Some(Set.empty[App]),
        Some(Set(
          GroupUpdate(
            Some("test"), Some(Set.empty[App]), Some(Set(Group.emptyUpdate("foo".toPath)))),
          GroupUpdate(
            Some("apps"), Some(Set(
              App("app1", cmd = Some("foo"),
                dependencies = Set("d1", "../test/foo", "/test")))))
        ))
      )
      val timestamp = Timestamp.now()

      When("The update is performed")
      val result: Group = Raml.fromRaml(GroupConversion(update, rootGroup, timestamp) -> appConversionFunc)

      validate(RootGroup.fromGroup(result))(RootGroup.rootGroupValidator(noEnabledFeatures)).isSuccess should be(true)

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
        Some(PathId.empty.toString),
        Some(Set.empty[App]),
        Some(Set(
          GroupUpdate(
            Some("test"),
            None,
            Some(Set(Group.emptyUpdate("foo".toPath)))
          ),
          GroupUpdate(
            Some("apps"),
            Some(Set(App("app1", cmd = Some("foo"),
              dependencies = Set("d1", "../test/foo", "/test"))))
          )
        ))
      )
      val timestamp = Timestamp.now()

      When("The update is performed")
      val result: RootGroup = RootGroup.fromGroup(Raml.fromRaml(
        GroupConversion(update, actual, timestamp) -> appConversionFunc))

      validate(result)(RootGroup.rootGroupValidator(Set())).isSuccess should be(true)

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
        Some("/test"),
        Some(Set.empty[App]),
        Some(Set(
          GroupUpdate(Some("/test/group1"), Some(Set(App("/test/group1/app3", cmd = Some("foo"))))),
          GroupUpdate(
            Some("/test/group3"),
            Some(Set.empty[App]),
            Some(Set(GroupUpdate(Some("/test/group3/sub1"), Some(Set(App(
              "/test/group3/sub1/app4", cmd =
                Some("foo")))))))
          )
        ))
      )

      val timestamp = Timestamp.now()
      val next = Raml.fromRaml(GroupConversion(update, current, timestamp) -> appConversionFunc)
      val result = createRootGroup(groups = Set(next))

      validate(result)(RootGroup.rootGroupValidator(Set())).isSuccess should be(true)

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
      val update = GroupUpdate(None, version = Some(Timestamp.now().toOffsetDateTime))
      intercept[IllegalArgumentException] {
        Raml.fromRaml(GroupConversion(update, createRootGroup(), Timestamp.now()) -> appConversionFunc)
      }
    }

    "A group update should not contain a scaleBy" in {
      val update = GroupUpdate(None, scaleBy = Some(3))
      intercept[IllegalArgumentException] {
        Raml.fromRaml(GroupConversion(update, createRootGroup(), Timestamp.now()) -> appConversionFunc)
      }
    }

    "Relative path of a dependency, should be relative to group and not to the app" in {
      Given("A group with two apps. Second app is dependend of first.")
      val update = GroupUpdate(Some(PathId.empty.toString), Some(Set.empty[App]), Some(Set(
        GroupUpdate(
          Some("test-group"),
          Some(Set(
            App("test-app1", cmd = Some("foo")),
            App("test-app2", cmd = Some("foo"), dependencies = Set("test-app1"))))
        )
      )))

      When("The update is performed")
      val result = Raml.fromRaml(
        GroupConversion(update, createRootGroup(), Timestamp.now()) -> appConversionFunc)

      validate(RootGroup.fromGroup(result))(RootGroup.rootGroupValidator(noEnabledFeatures)).isSuccess should be(true)

      Then("The update is applied correctly")
      val group = result.group("test-group".toRootPath)
      group should be('defined)
      group.get.apps should have size 2
      val dependentApp = group.get.app("/test-group/test-app2".toPath).get
      dependentApp.dependencies should be (Set("/test-group/test-app1".toPath))
    }
  }
}
