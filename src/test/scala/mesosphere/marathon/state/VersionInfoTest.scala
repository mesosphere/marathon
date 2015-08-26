package mesosphere.marathon.state

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.AppDefinition.VersionInfo.FullVersionInfo
import org.scalatest.{ Matchers, GivenWhenThen }

class VersionInfoTest extends MarathonSpec with GivenWhenThen with Matchers {
  test("NoVersion upgrades to FullVersion on a scaling change") {
    Given("NoVersion")
    val versionInfo = AppDefinition.VersionInfo.NoVersion
    val versionOfNoVersion = versionInfo.version

    When("Applying a scaling change")
    val newVersion = versionInfo.withScaleOrRestartChange(Timestamp(1))

    Then("The version info is promoted to a FullVersion")
    newVersion should be(
      FullVersionInfo(
        version = Timestamp(1),
        lastScalingAt = Timestamp(1),
        lastConfigChangeAt = versionOfNoVersion
      )
    )
  }

  test("NoVersion upgrades to FullVersion on a config change") {
    Given("NoVersion")
    val versionInfo = AppDefinition.VersionInfo.NoVersion

    When("Applying a config change")
    val newVersion = versionInfo.withConfigChange(Timestamp(1))

    Then("The version info is promoted to a FullVersion")
    newVersion should be(
      FullVersionInfo(
        version = Timestamp(1),
        lastScalingAt = Timestamp(1),
        lastConfigChangeAt = Timestamp(1)
      )
    )
  }

  test("OnlyVersion upgrades to FullVersion on a scaling change") {
    Given("An OnlyVersion info")
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(1))

    When("Applying a scaling change")
    val newVersion = versionInfo.withScaleOrRestartChange(Timestamp(2))

    Then("The version info is promoted to a FullVersion")
    newVersion should be(
      FullVersionInfo(
        version = Timestamp(2),
        lastScalingAt = Timestamp(2),
        lastConfigChangeAt = Timestamp(1)
      )
    )
  }

  test("OnlyVersion upgrades to FullVersion on a config change") {
    Given("An OnlyVersion info")
    val versionInfo = AppDefinition.VersionInfo.OnlyVersion(Timestamp(1))

    When("Applying a config change")
    val newVersion = versionInfo.withConfigChange(Timestamp(2))

    Then("The version info is promoted to a FullVersion")
    newVersion should be(
      FullVersionInfo(
        version = Timestamp(2),
        lastScalingAt = Timestamp(2),
        lastConfigChangeAt = Timestamp(2)
      )
    )
  }

  test("A scaling change on FullVersion only changes scalingAt") {
    Given("A FullVersionInfo")
    val versionInfo = AppDefinition.VersionInfo.FullVersionInfo(
      version = Timestamp(2),
      lastScalingAt = Timestamp(2),
      lastConfigChangeAt = Timestamp(1)
    )

    When("Applying a scaling change")
    val newVersion = versionInfo.withScaleOrRestartChange(Timestamp(3))

    Then("The version info is promoted to a FullVersion")
    newVersion should be(
      FullVersionInfo(
        version = Timestamp(3),
        lastScalingAt = Timestamp(3),
        lastConfigChangeAt = Timestamp(1)
      )
    )
  }

  test("A config change on FullVersion changes scalingAt, lastConfigChangeAt") {
    Given("A FullVersionInfo")
    val versionInfo = AppDefinition.VersionInfo.FullVersionInfo(
      version = Timestamp(2),
      lastScalingAt = Timestamp(2),
      lastConfigChangeAt = Timestamp(1)
    )

    When("Applying a scaling change")
    val newVersion = versionInfo.withConfigChange(Timestamp(3))

    Then("The version info is promoted to a FullVersion")
    newVersion should be(
      FullVersionInfo(
        version = Timestamp(3),
        lastScalingAt = Timestamp(3),
        lastConfigChangeAt = Timestamp(3)
      )
    )
  }
}
