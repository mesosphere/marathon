package mesosphere.marathon

import mesosphere.UnitTest

class DeprecatedFeaturesConfigTest extends UnitTest {
  val currentVersion = SemVer(1, 6, 0)
  val hardRemovedIn170 =
    DeprecatedFeature("test", description = "", softRemoveVersion = SemVer(1, 6, 0), hardRemoveVersion = SemVer(1, 7, 0))
  val softRemovedIn170 =
    DeprecatedFeature("test", description = "", softRemoveVersion = SemVer(1, 7, 0), hardRemoveVersion = SemVer(1, 8, 0))

  "isValid()" should {
    "returns true when only softRemoved features are enabled" in {
      val deprecatedFeatureConfig =
        DeprecatedFeatureConfig(currentVersion = currentVersion, deprecatedFeatureSettings = Map(hardRemovedIn170 -> true))

      deprecatedFeatureConfig.isValid() shouldBe true
    }

    "returns false when hardRemoved features are enabled" in {
      val deprecatedFeatureConfig =
        DeprecatedFeatureConfig(currentVersion = SemVer(1, 7, 0), deprecatedFeatureSettings = Map(hardRemovedIn170 -> true))

      deprecatedFeatureConfig.isValid() shouldBe false
    }

    "returns true when hardRemoved features are disabled" in {
      val deprecatedFeatureConfig =
        DeprecatedFeatureConfig(currentVersion = SemVer(1, 7, 0), deprecatedFeatureSettings = Map(hardRemovedIn170 -> false))

      deprecatedFeatureConfig.isValid() shouldBe true
    }
  }

  "isEnabled(...)" should {
    "returns true for explicitly enabled features" in {
      val deprecatedFeatureConfig =
        DeprecatedFeatureConfig(currentVersion = currentVersion, deprecatedFeatureSettings = Map(hardRemovedIn170 -> true))

      deprecatedFeatureConfig.isEnabled(hardRemovedIn170) shouldBe (true)
    }

    "returns false for soft-removed features not explicitly enabled" in {
      DeprecatedFeatureConfig.empty(currentVersion).isEnabled(hardRemovedIn170) shouldBe false
    }

    "returns true for features not yet soft-removed" in {
      val deprecatedFeatureConfig = DeprecatedFeatureConfig(currentVersion = currentVersion, Map.empty)

      deprecatedFeatureConfig.isEnabled(softRemovedIn170) shouldBe (true)
    }

    "returns false for features not yet soft-removed but explicitly disabled" in {
      val deprecatedFeatureConfig =
        DeprecatedFeatureConfig(currentVersion = currentVersion, deprecatedFeatureSettings = Map(softRemovedIn170 -> false))

      deprecatedFeatureConfig.isEnabled(softRemovedIn170) shouldBe (false)
    }
  }

}
