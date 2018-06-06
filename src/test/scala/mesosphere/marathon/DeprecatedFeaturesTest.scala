package mesosphere.marathon

import mesosphere.UnitTest

class DeprecatedFeaturesTest extends UnitTest {
  val currentVersion = SemVer(1, 6, 0)
  val hardRemovedIn170 = DeprecatedFeature("test", description = "", softRemoveVersion = SemVer(1, 6, 0), hardRemoveVersion = SemVer(1, 7, 0))
  val softRemovedIn170 = DeprecatedFeature("test", description = "", softRemoveVersion = SemVer(1, 7, 0), hardRemoveVersion = SemVer(1, 8, 0))

  "DeprecatedFeatureSet" should {
    val fixture = DeprecatedFeatureSet(
      currentVersion = currentVersion,
      enabledDeprecatedFeatures = Set(hardRemovedIn170))

    "isValid()" should {
      "return true when softRemoved features are included" in {
        fixture.isValid() shouldBe true
      }

      "return false when hardRemoved features are included" in {
        fixture.copy(currentVersion = SemVer(1, 7, 0)).isValid() shouldBe false
      }
    }

    "isEnabled(...)" should {
      "return true for explicitly enabled features" in {
        fixture.isEnabled(hardRemovedIn170) shouldBe (true)
      }

      "return false for soft-removed features not explicitly enabled" in {
        DeprecatedFeatureSet(currentVersion, Set.empty).isEnabled(hardRemovedIn170) shouldBe false
      }

      "return true for features not yet soft-removed" in {
        fixture.isEnabled(softRemovedIn170) shouldBe (true)
      }
    }
  }
}
