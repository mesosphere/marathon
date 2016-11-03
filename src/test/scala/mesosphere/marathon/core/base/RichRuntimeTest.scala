package mesosphere.marathon.core.base

import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.ExitDisabledTest

import scala.concurrent.duration._

class RichRuntimeTest extends AkkaUnitTest with ExitDisabledTest {
  "RichRuntime.asyncExit" should {
    "call exit" in {
      Runtime.getRuntime.asyncExit(123, 1.millisecond)
      exitCalled(123).futureValue should be(true)
      // the timer will expire no matter what
      exitCalled(123).futureValue should be(true)
    }
  }
}

