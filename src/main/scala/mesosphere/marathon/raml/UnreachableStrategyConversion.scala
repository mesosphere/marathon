package mesosphere.marathon
package raml

import scala.concurrent.duration._

/**
  * Conversion from [[mesosphere.marathon.state.UnreachableStrategy]] to [[mesosphere.marathon.raml.UnreachableStrategy]].
  */
trait UnreachableStrategyConversion {

  implicit val ramlUnreachableStrategyRead = Reads[UnreachableStrategy, state.UnreachableStrategy] {
    case strategy: UnreachableEnabled =>
      state.UnreachableEnabled(
        inactiveAfter = strategy.inactiveAfterSeconds.seconds,
        expungeAfter = strategy.expungeAfterSeconds.seconds)
    case _: UnreachableDisabled =>
      state.UnreachableDisabled
  }

  implicit val ramlUnreachableStrategyWrite = Writes[state.UnreachableStrategy, UnreachableStrategy]{
    case strategy: state.UnreachableEnabled =>
      UnreachableEnabled(
        inactiveAfterSeconds = strategy.inactiveAfter.toSeconds,
        expungeAfterSeconds = strategy.expungeAfter.toSeconds)
    case state.UnreachableDisabled =>
      UnreachableDisabled("disabled")
  }
}

object UnreachableStrategyConversion extends UnreachableStrategyConversion
