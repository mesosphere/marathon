package mesosphere.marathon
package raml

import mesosphere.marathon.state
import mesosphere.UnitTest

import scala.concurrent.duration._

class UnreachableStrategyConversionTest extends UnitTest {

  "UnreachableStrategyConversion" should {
    "read from RAML" in {
      val raml = UnreachableEnabled()

      val result: state.UnreachableStrategy = UnreachableStrategyConversion.ramlUnreachableStrategyRead(raml)

      result shouldBe (state.UnreachableStrategy.default(resident = false))
    }
  }

  it should {
    "write to RAML" in {
      val strategy = state.UnreachableEnabled(inactiveAfter = 10.minutes, expungeAfter = 20.minutes)

      val raml = UnreachableStrategyConversion.ramlUnreachableStrategyWrite(strategy).
        asInstanceOf[UnreachableEnabled]

      raml.inactiveAfterSeconds should be(600)
      raml.expungeAfterSeconds should be(1200)
    }
  }
}
