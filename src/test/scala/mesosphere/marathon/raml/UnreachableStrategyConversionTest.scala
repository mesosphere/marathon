package mesosphere.marathon
package raml

import mesosphere.marathon.state
import mesosphere.UnitTest

import scala.concurrent.duration._

class UnreachableStrategyConversionTest extends UnitTest {

  "UnreachableStrategyConversion" should {
    "read from RAML" in {
      val raml = UnreachableStrategy()

      val result: state.UnreachableStrategy = UnreachableStrategyConversion.ramlRead(raml)

      result.timeUntilInactive should be(5.minutes)
      result.timeUntilExpunge should be(10.minutes)
    }
  }

  it should {
    "write to RAML" in {
      val strategy = state.UnreachableStrategy(10.minutes, 20.minutes)

      val raml: UnreachableStrategy = UnreachableStrategyConversion.ramlWrite(strategy)

      raml.timeUntilInactiveSeconds should be(600)
      raml.timeUntilExpungeSeconds should be(1200)
    }
  }
}
