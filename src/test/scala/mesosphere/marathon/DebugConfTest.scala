package mesosphere.marathon

import mesosphere.UnitTest
import mesosphere.marathon.test.MarathonTestHelper

class DebugConfTest extends UnitTest {
  "Metrics" should {
    "not be enabled by default" in {
      val conf = MarathonTestHelper.defaultConfig()
      conf.metrics() should be (false)
    }

    "metrics can be enabled" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--metrics"
      )
      conf.metrics() should be(true)
    }

    "metrics can be disabled" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--disable_metrics"
      )
      conf.metrics() should be(false)
    }
  }
}
