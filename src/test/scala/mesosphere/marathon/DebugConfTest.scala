package mesosphere.marathon

import mesosphere.UnitTest
import mesosphere.marathon.test.MarathonTestHelper

class DebugConfTest extends UnitTest {
  "DebugConf" should {
    "tracing is disabled by default" in {
      val conf = MarathonTestHelper.defaultConfig()
      assert(!conf.debugTracing())
    }

    "tracing can be enabled" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--tracing"
      )
      assert(conf.enableDebugTracing)
    }

    "tracing can be enabled using the deprecated ---enable_tracing" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--enable_tracing"
      )
      assert(conf.enableDebugTracing)
    }

    "tracing can be disabled" in {
      val conf = MarathonTestHelper.makeConfig("" +
        "--master", "127.0.0.1:5050",
        "--disable_tracing"
      )
      assert(!conf.enableDebugTracing)
    }

    "metrics are enabled by default" in {
      val conf = MarathonTestHelper.defaultConfig()
      assert(conf.metrics())
    }

    "metrics can be enabled" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--metrics"
      )
      assert(conf.metrics())
    }

    "the deprecated --enable_metrics is accepted" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--enable_metrics"
      )
      assert(conf.metrics())
    }

    "metrics can be disabled" in {
      val conf = MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--disable_metrics"
      )
      assert(!conf.metrics())
    }
  }
}
