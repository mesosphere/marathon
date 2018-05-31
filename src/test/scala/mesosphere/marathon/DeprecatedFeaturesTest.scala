package mesosphere.marathon

import mesosphere.UnitTest

class DeprecatedFeaturesTest extends UnitTest {
  import DeprecatedFeatures._
  val removedIn170 = DeprecatedFeature("test", description = "", warnVersion = SemVer(1, 6, 0), removeVersion = SemVer(1, 7, 0))
  "allDeprecatedFeaturesActive" should {
    "return true for warning features" in {
      allDeprecatedFeaturesActive(Seq(removedIn170), SemVer(1, 6, 0))
    }

    "return false for removed features" in {
      allDeprecatedFeaturesActive(Seq(removedIn170), SemVer(1, 7, 0))
    }
  }
}
