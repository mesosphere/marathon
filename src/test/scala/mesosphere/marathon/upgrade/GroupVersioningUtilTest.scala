package mesosphere.marathon.upgrade

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.AppDefinition.VersionInfo
import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp, Group }
import org.scalatest.{ Matchers, GivenWhenThen }

class GroupVersioningUtilTest extends MarathonSpec with GivenWhenThen with Matchers {
  val emptyGroup = Group.empty.copy(version = Timestamp(1))

  val nestedApp = Group.empty.copy(
    groups = Set(
      Group(
        id = PathId("/nested"),
        apps = Set(
          AppDefinition(PathId("/nested/app"), cmd = Some("sleep 123"))
        ),
        version = Timestamp(2)
      )
    ),
    version = Timestamp(2)
  )

  val nestedAppScaled = Group.empty.copy(
    groups = Set(
      Group(
        id = PathId("/nested"),
        apps = Set(
          AppDefinition(PathId("/nested/app"), cmd = Some("sleep 123"), instances = 2)
        ),
        version = Timestamp(2)
      )
    ),
    version = Timestamp(2)
  )

  val nestedAppUpdated = Group.empty.copy(
    groups = Set(
      Group(
        id = PathId("/nested"),
        apps = Set(
          AppDefinition(PathId("/nested/app"), cmd = Some("sleep 234"))
        ),
        version = Timestamp(2)
      )
    ),
    version = Timestamp(2)
  )

  test("No changes for empty group") {
    When("Calculating version infos for an empty group")
    val updated = GroupVersioningUtil.updateVersionInfoForChangedApps(Timestamp(10), emptyGroup, emptyGroup)
    Then("nothing is changed")
    updated should be(emptyGroup)
  }

  test("No changes for nested app") {
    When("Calculating version infos with no changes")
    val updated = GroupVersioningUtil.updateVersionInfoForChangedApps(Timestamp(10), nestedApp, nestedApp)
    Then("nothing is changed")
    updated should be(nestedApp)
  }

  test("A new app should get proper versionInfo") {
    When("Calculating version infos with an added app")
    val updated = GroupVersioningUtil.updateVersionInfoForChangedApps(Timestamp(10), emptyGroup, nestedApp)
    Then("The timestamp of the app and groups are updated appropriately")
    def update(maybeApp: Option[AppDefinition]): AppDefinition =
      maybeApp.map(_.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(10)))).get
    updated should be(nestedApp.updateApp(
      PathId("/nested/app"),
      update,
      Timestamp(10)
    ))
  }

  test("A scaled app should get proper versionInfo") {
    When("Calculating version infos with a scaled app")
    val updated = GroupVersioningUtil.updateVersionInfoForChangedApps(Timestamp(10), nestedApp, nestedAppScaled)
    Then("The timestamp of the app and groups are updated appropriately")
    def update(maybeApp: Option[AppDefinition]): AppDefinition =
      maybeApp.map(_.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(0)).withScaleOrRestartChange(Timestamp(10)))).get
    updated.toString should be(nestedAppScaled.updateApp(
      PathId("/nested/app"),
      update,
      Timestamp(10)
    ).toString)
  }

  test("A updated app should get proper versionInfo") {
    When("Calculating version infos with an updated app")
    val updated = GroupVersioningUtil.updateVersionInfoForChangedApps(Timestamp(10), nestedApp, nestedAppUpdated)
    Then("The timestamp of the app and groups are updated appropriately")
    def update(maybeApp: Option[AppDefinition]): AppDefinition =
      maybeApp.map(_.copy(versionInfo = VersionInfo.forNewConfig(Timestamp(10)))).get
    updated.toString should be(nestedAppUpdated.updateApp(
      PathId("/nested/app"),
      update,
      Timestamp(10)
    ).toString)
  }
}
