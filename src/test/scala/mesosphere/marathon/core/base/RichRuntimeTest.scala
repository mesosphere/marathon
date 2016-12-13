package mesosphere.marathon.core.base

import mesosphere.{ AkkaUnitTest, Unstable }
import mesosphere.marathon.test.ExitDisabledTest

import scala.concurrent.duration._

class RichRuntimeTest extends AkkaUnitTest with ExitDisabledTest {
  "RichRuntime.asyncExit" should {
    "call exit" taggedAs (Unstable) in {
      Runtime.getRuntime.asyncExit(123, 1.millisecond)
      exitCalled(123).futureValue should be(true)
    }
  }
}

