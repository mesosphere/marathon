package mesosphere.marathon

import mesosphere.UnitTest

class MainTest extends UnitTest {
  "envToArgs" should {
    "return boolean flags for empty string values" in {
      Main.envToArgs(Map("MARATHON_DISABLE_HA" -> "")) shouldBe Seq("--disable_ha")
    }

    "return downcased parameter flags for non-empty strings" in {
      Main.envToArgs(Map("MARATHON_HTTP_PORT" -> "8080")) shouldBe Seq("--http_port", "8080")
    }

    "ignores strings not beginning with MARATHON_" in {
      Main.envToArgs(Map("CMD_MARATHON_HTTP_PORT" -> "8080")) shouldBe Seq()
      Main.envToArgs(Map("marathon_http_port" -> "8080")) shouldBe Seq()
    }
  }
}
