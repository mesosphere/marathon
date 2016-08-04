package mesosphere.marathon.util

import mesosphere.UnitTest

import scala.concurrent.Future

class RichFutureTest extends UnitTest {
  "RichFuture" should {
    "complete with a Success when successful" in {
      Future.successful(1).asTry.futureValue.success.value should equal(1)
    }
    "fail with a Failure when not successful" in {
      val ex = new Exception
      Future.failed(ex).asTry.futureValue.failure.exception should be(ex)
    }
  }
}
