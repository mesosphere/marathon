package mesosphere.marathon
package raml

import scala.concurrent.duration._

/**
  * Conversion from [[mesosphere.marathon.state.UnreachableStrategy]] to [[mesosphere.marathon.raml.UnreachableStrategy]].
  */
trait UnreachableStrategyConversion {

  implicit val ramlUnreachableStrategyRead = Reads[UnreachableStrategy, state.UnreachableStrategy] { strategy =>
    state.UnreachableStrategy(
      inactiveAfter = strategy.inactiveAfterSeconds.seconds,
      expungeAfter = strategy.expungeAfterSeconds.seconds)
  }

  implicit val ramlUnreachableStrategyWrite = Writes[state.UnreachableStrategy, UnreachableStrategy]{ strategy =>
    UnreachableStrategy(
      inactiveAfterSeconds = strategy.inactiveAfter.toSeconds,
      expungeAfterSeconds = strategy.expungeAfter.toSeconds)
  }
}

object UnreachableStrategyConversion extends UnreachableStrategyConversion
