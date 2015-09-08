package mesosphere

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Seconds, Span }

/**
  * ScalaFutures from scalatest with a different default configuration.
  */
trait FutureTestSupport extends ScalaFutures {
  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(3, Seconds))
}

object FutureTestSupport extends FutureTestSupport
