package mesosphere.marathon
package raml

import mesosphere.marathon.state
import scala.concurrent.duration._

/**
  * Conversion from [[mesosphere.marathon.state.UnreachableStrategy]] to [[mesosphere.marathon.raml.UnreachableStrategy]].
  */
trait UnreachableStrategyConversion {

  implicit val ramlRead = Reads[UnreachableStrategy, state.UnreachableStrategy] { handling =>
    state.UnreachableStrategy(
      timeUntilInactive = handling.timeUntilInactiveSeconds.seconds,
      timeUntilExpunge = handling.timeUntilExpungeSeconds.seconds)
  }

  implicit val ramlWrite = Writes[state.UnreachableStrategy, UnreachableStrategy]{ handling =>
    UnreachableStrategy(
      timeUntilInactiveSeconds = handling.timeUntilInactive.toSeconds,
      timeUntilExpungeSeconds = handling.timeUntilExpunge.toSeconds)
  }
}

object UnreachableStrategyConversion extends UnreachableStrategyConversion
