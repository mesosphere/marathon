package mesosphere.marathon

import mesosphere.UnitTest

class BuildInfoTest extends UnitTest {

  "BuildInfo" should {
    "return a default versions" in {
      BuildInfo.scalaVersion should be("2.x.x")
      BuildInfo.version should be(SemVer(1, 6, 0, Some("SNAPSHOT")))
    }
  }
}
