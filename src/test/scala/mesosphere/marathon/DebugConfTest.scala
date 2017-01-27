package mesosphere.marathon

import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper }

class DebugConfTest extends MarathonSpec {
  test("tracing is disabled by default") {
    val conf = MarathonTestHelper.defaultConfig()
    assert(!conf.enableDebugTracing)
  }

  test("tracing can be enabled") {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--tracing"
    )
    assert(conf.enableDebugTracing)
  }

  test("tracing can be disabled") {
    val conf = MarathonTestHelper.makeConfig("" +
      "--master", "127.0.0.1:5050",
      "--disable_tracing"
    )
    assert(!conf.enableDebugTracing)
  }

  test("metrics are enabled by default") {
    val conf = MarathonTestHelper.defaultConfig()
    assert(conf.enableMetrics)
  }

  test("metrics can be enabled") {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--metrics"
    )
    assert(conf.enableMetrics)
  }

  test("metrics can be disabled") {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--disable_metrics"
    )
    assert(!conf.enableMetrics)
  }
}
