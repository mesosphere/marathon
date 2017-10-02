package mesosphere

import org.scalatest.concurrent.{ JavaFutures, ScalaFutures }
import org.scalatest.time.{ Seconds, Span }

/**
  * ScalaFutures from scalatest with a different default configuration.
  */
trait FutureTestSupport extends ScalaFutures with JavaFutures {
  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))
}

object FutureTestSupport extends FutureTestSupport

trait IntegrationFutureTestSupport extends ScalaFutures with JavaFutures {
  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(90, Seconds))
}
