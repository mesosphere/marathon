package mesosphere.marathon
package state

import mesosphere.UnitTest
import mesosphere.marathon.state.VersionInfo.FullVersionInfo

class VersionInfoTest extends UnitTest {
  "VersionInfo" should {
    "NoVersion upgrades to FullVersion on a scaling change" in {
      Given("NoVersion")
      val versionInfo = VersionInfo.NoVersion
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

    "NoVersion upgrades to FullVersion on a config change" in {
      Given("NoVersion")
      val versionInfo = VersionInfo.NoVersion

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

    "OnlyVersion upgrades to FullVersion on a scaling change" in {
      Given("An OnlyVersion info")
      val versionInfo = VersionInfo.OnlyVersion(Timestamp(1))

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

    "OnlyVersion upgrades to FullVersion on a config change" in {
      Given("An OnlyVersion info")
      val versionInfo = VersionInfo.OnlyVersion(Timestamp(1))

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

    "A scaling change on FullVersion only changes scalingAt" in {
      Given("A FullVersionInfo")
      val versionInfo = VersionInfo.FullVersionInfo(
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

    "A config change on FullVersion changes scalingAt, lastConfigChangeAt" in {
      Given("A FullVersionInfo")
      val versionInfo = VersionInfo.FullVersionInfo(
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
}

