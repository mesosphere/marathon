package mesosphere.marathon
package upgrade

import mesosphere.UnitTest
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation

class GroupVersioningUtilTest extends UnitTest with GroupCreation {
  val emptyGroup = createRootGroup(version = Timestamp(1))

  val app =
    AppDefinition(AbsolutePathId("/nested/app"), role = "*", cmd = Some("sleep 123"), versionInfo = VersionInfo.OnlyVersion(Timestamp.zero))

  val nestedApp = createRootGroup(
    groups = Set(
      createGroup(
        id = AbsolutePathId("/nested"),
        apps = Map(app.id -> app),
        version = Timestamp(2)
      )
    ),
    version = Timestamp(2)
  )

  val scaledApp = AppDefinition(
    AbsolutePathId("/nested/app"),
    role = "*",
    cmd = Some("sleep 123"),
    instances = 2,
    versionInfo = VersionInfo.OnlyVersion(Timestamp.zero)
  )

  val nestedAppScaled = createRootGroup(
    groups = Set(
      createGroup(
        id = AbsolutePathId("/nested"),
        apps = Map(scaledApp.id -> scaledApp),
        version = Timestamp(2)
      )
    ),
    version = Timestamp(2)
  )

  val updatedApp = AppDefinition(AbsolutePathId("/nested/app"), role = "*", cmd = Some("sleep 234"))

  val nestedAppUpdated = createRootGroup(
    groups = Set(
      createGroup(
        id = AbsolutePathId("/nested"),
        apps = Map(updatedApp.id -> updatedApp),
        version = Timestamp(2)
      )
    ),
    version = Timestamp(2)
  )

  "GroupVersioningUtil" should {
    "No changes for empty group should result in the resulting root group version set to the given one" in {
      When("Calculating version infos for an empty group")
      val updated = GroupVersioningUtil.updateVersionInfoForChangedApps(Timestamp(10), emptyGroup, emptyGroup)
      Then("the version is updated anyway")
      updated should be(emptyGroup.updateVersion(Timestamp(10)))
    }

    "No changes for nested app should result in the resulting root group version set to the given one" in {
      When("Calculating version infos with no changes")
      val updated = GroupVersioningUtil.updateVersionInfoForChangedApps(Timestamp(10), nestedApp, nestedApp)
      Then("the version is updated anyway")
      updated should be(nestedApp.updateVersion(Timestamp(10)))
    }

    "A new app should get proper versionInfo" in {
      When("Calculating version infos with an added app")
      val updated = GroupVersioningUtil.updateVersionInfoForChangedApps(Timestamp(10), emptyGroup, nestedApp)
      Then("The timestamp of the app and groups are updated appropriately")
      def update(maybeApp: Option[AppDefinition]): AppDefinition =
        maybeApp.map(_.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(10)))).get
      updated should be(
        nestedApp.updateApp(
          AbsolutePathId("/nested/app"),
          update,
          Timestamp(10)
        )
      )
    }

    "A scaled app should get proper versionInfo" in {
      When("Calculating version infos with a scaled app")
      val updated = GroupVersioningUtil.updateVersionInfoForChangedApps(Timestamp(10), nestedApp, nestedAppScaled)
      Then("The timestamp of the app and groups are updated appropriately")
      def update(maybeApp: Option[AppDefinition]): AppDefinition =
        maybeApp.map(_.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(0)).withScaleOrRestartChange(Timestamp(10)))).get
      updated should equal(
        nestedAppScaled.updateApp(
          AbsolutePathId("/nested/app"),
          update,
          Timestamp(10)
        )
      )
    }

    "A updated app should get proper versionInfo" in {
      When("Calculating version infos with an updated app")
      val updated = GroupVersioningUtil.updateVersionInfoForChangedApps(Timestamp(10), nestedApp, nestedAppUpdated)
      Then("The timestamp of the app and groups are updated appropriately")
      def update(maybeApp: Option[AppDefinition]): AppDefinition =
        maybeApp.map(_.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(10)))).get
      updated.toString should be(
        nestedAppUpdated
          .updateApp(
            AbsolutePathId("/nested/app"),
            update,
            Timestamp(10)
          )
          .toString
      )
    }
  }
}
