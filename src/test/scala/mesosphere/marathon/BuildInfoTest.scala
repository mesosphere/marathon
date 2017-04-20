package mesosphere.marathon

import mesosphere.UnitTest

class BuildInfoTest extends UnitTest {

  "BuildInfo" should {
    "return a default versions" in {
      BuildInfo.scalaVersion should be("2.x.x")
      BuildInfo.version should be("1.5.0-SNAPSHOT")
    }
  }
}
