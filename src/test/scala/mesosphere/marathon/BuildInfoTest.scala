package mesosphere.marathon

import mesosphere.UnitTest

class BuildInfoTest extends UnitTest {

  "BuildInfo" should {
    "return a default version" in {
      BuildInfo.scalaVersion should be("2.x.x")
      BuildInfo.version.commit.shouldBe(Some("SNAPSHOT"))
    }
  }
}
