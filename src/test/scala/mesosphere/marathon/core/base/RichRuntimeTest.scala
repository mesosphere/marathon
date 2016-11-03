package mesosphere.marathon.core.base

import java.util.concurrent.Semaphore

import mesosphere.AkkaUnitTest
import mesosphere.marathon.test.ExitDisabledTest
import scala.concurrent.duration._

class RichRuntimeTest extends AkkaUnitTest with ExitDisabledTest {
  "RichRuntime.asyncExit" should {
    "call exit" in {
      Runtime.getRuntime.asyncExit(123)
      exitCalled(123).futureValue should be(true)
    }
    "halt after the timeout if exit was blocked" in {
      val sem = new Semaphore(0)
      val hook = sys.addShutdownHook(sem.acquire())
      try {
        Runtime.getRuntime.asyncExit(125, 1.millis)
        exitCalled(125).futureValue should be(true)
        // no way to disambiguate halt and exit so we can only check if exit is called twice.
        exitCalled(125).futureValue should be(true)
      } finally {
        hook.remove()
        sem.release()
      }
    }
  }
}
