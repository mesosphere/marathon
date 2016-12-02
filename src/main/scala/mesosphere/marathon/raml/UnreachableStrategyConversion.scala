package mesosphere.marathon
package raml

import scala.concurrent.duration._

/**
  * Conversion from [[mesosphere.marathon.state.UnreachableStrategy]] to [[mesosphere.marathon.raml.UnreachableStrategy]].
  */
trait UnreachableStrategyConversion {

  implicit val ramlUnreachableStrategyRead = Reads[UnreachableStrategy, state.UnreachableStrategy] { handling =>
    state.UnreachableStrategy(
      unreachableInactiveAfter = handling.unreachableInactiveAfterSeconds.seconds,
      unreachableExpungeAfter = handling.unreachableExpungeAfterSeconds.seconds)
  }

  implicit val ramlUnreachableStrategyWrite = Writes[state.UnreachableStrategy, UnreachableStrategy]{ handling =>
    UnreachableStrategy(
      unreachableInactiveAfterSeconds = handling.unreachableInactiveAfter.toSeconds,
      unreachableExpungeAfterSeconds = handling.unreachableExpungeAfter.toSeconds)
  }
}

object UnreachableStrategyConversion extends UnreachableStrategyConversion
