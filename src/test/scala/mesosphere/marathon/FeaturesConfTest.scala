package mesosphere.marathon

import mesosphere.UnitTest
import mesosphere.marathon.test.MarathonTestHelper
import org.scalatest.Inside
import scala.util.{Failure, Try}

class FeaturesConfTest extends UnitTest with Inside {

  "Features should be empty by default" in {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050"
    )

    conf.features() should be(empty)
  }

  "Features should allow vips" in {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--enable_features", "vips"
    )

    conf.availableFeatures should be(Set("vips"))
  }

  "Features should allow multiple entries" in {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--enable_features", "gpu_resources, vips"
    )

    conf.availableFeatures should be(Set("gpu_resources", "vips"))
  }

  "Features should not allow unknown features" in {
    val confTry = Try(
      MarathonTestHelper.makeConfig(
        "--master", "127.0.0.1:5050",
        "--enable_features", "unknown"
      )
    )

    confTry.isFailure should be(true)
    confTry.failed.get.getMessage should include("Unknown features specified: unknown.")
  }

  "deprecatedFeatures should be empty by default" in {
    val conf = MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050"
    )

    conf.deprecatedFeatures().enabledDeprecatedFeatures should be(empty)
  }

  "deprecatedFeatures should not allow unknown deprecated features" in {
    inside(Try(MarathonTestHelper.makeConfig(
      "--master", "127.0.0.1:5050",
      "--deprecated_features", "unknown"))) {
      case Failure(ex) =>
        ex.getMessage should include("Unknown deprecated features specified: unknown")
    }
  }
}
