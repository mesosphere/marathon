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

    "ignores strings beginning with MARATHON_APP_" in {
      Main.envToArgs(Map("MARATHON_APP_VERSION" -> "1.5")) shouldBe Seq()
    }
  }

  "configToLogLines" should {
    val conf = AllConf(
      "--master",
      "zk://super:secret@127.0.0.1:2181/master",
      "--zk",
      "zk://also:special@localhost:2181/marathon",
      "--mesos_role",
      "super"
    )
    "redact credentials from Zookeeper" in {
      val lines = Main.configToLogLines(conf).split("\n")
      val Some(masterLine) = lines.find(_.startsWith(" - master "))
      masterLine shouldBe " - master (*) = zk://xxxxxxxx:xxxxxxxx@127.0.0.1:2181/master"

      val Some(zkLine) = lines.find(_.startsWith(" - zk "))
      zkLine shouldBe " - zk (*) = zk://xxxxxxxx:xxxxxxxx@localhost:2181/marathon"
    }
  }
}
