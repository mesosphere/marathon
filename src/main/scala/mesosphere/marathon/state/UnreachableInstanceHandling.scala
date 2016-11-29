package mesosphere.marathon.state

import scala.concurrent.duration._

/**
  * Defines the time outs and kill strategy for unreachable tasks.
  */
case class UnreachableStrategy(
  timeUntilInactive: FiniteDuration = UnreachableStrategy.DefaultTimeUntilInactive,
  timeUntilExpunge: FiniteDuration = UnreachableStrategy.DefaultTimeUntilExpunge)

object UnreachableStrategy {
  val DefaultTimeUntilInactive = 3.minutes
  val DefaultTimeUntilExpunge = 6.minutes
}
