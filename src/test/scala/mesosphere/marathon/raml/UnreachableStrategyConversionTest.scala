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

      result.unreachableInactiveAfter should be(5.minutes)
      result.unreachableExpungeAfter should be(10.minutes)
    }
  }

  it should {
    "write to RAML" in {
      val strategy = state.UnreachableStrategy(10.minutes, 20.minutes)

      val raml: UnreachableStrategy = UnreachableStrategyConversion.ramlUnreachableStrategyWrite(strategy)

      raml.unreachableInactiveAfterSeconds should be(600)
      raml.unreachableExpungeAfterSeconds should be(1200)
    }
  }
}
