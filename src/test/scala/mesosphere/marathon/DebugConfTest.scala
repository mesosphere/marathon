package mesosphere.marathon

class DebugConfTest extends MarathonSpec {
  test("tracing is disabled by default") {
    val conf = MarathonTestHelper.defaultConfig()
    assert(!conf.debugTracing())
  }

  test("tracing can be enabled") {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--tracing"
    )
    assert(conf.enableDebugTracing)
  }

  test("tracing can be enabled using the deprecated ---enable_tracing") {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--enable_tracing"
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
    assert(conf.metrics())
  }

  test("metrics can be enabled") {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--metrics"
    )
    assert(conf.metrics())
  }

  test("the deprecated --enable_metrics is accepted") {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--enable_metrics"
    )
    assert(conf.metrics())
  }

  test("metrics can be disabled") {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--disable_metrics"
    )
    assert(!conf.metrics())
  }
}
