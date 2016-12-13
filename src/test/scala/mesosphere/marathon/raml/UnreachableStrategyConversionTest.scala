package mesosphere.marathon
package raml

import mesosphere.marathon.state
import mesosphere.UnitTest

import scala.concurrent.duration._

class UnreachableStrategyConversionTest extends UnitTest {

  "UnreachableStrategyConversion" should {
    "read from RAML" in {
      val raml = UnreachableStrategy()

      val result: state.UnreachableStrategy = UnreachableStrategyConversion.ramlUnreachableStrategyRead(raml)

      result.inactiveAfter should be(state.UnreachableStrategy.DefaultInactiveAfter)
      result.expungeAfter should be(state.UnreachableStrategy.DefaultExpungeAfter)
    }
  }

  it should {
    "write to RAML" in {
      val strategy = state.UnreachableStrategy(10.minutes, 20.minutes)

      val raml: UnreachableStrategy = UnreachableStrategyConversion.ramlUnreachableStrategyWrite(strategy)

      raml.inactiveAfterSeconds should be(600)
      raml.expungeAfterSeconds should be(1200)
    }
  }
}
