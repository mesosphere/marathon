package mesosphere.marathon
package state

import com.wix.accord.dsl._

import scala.concurrent.duration._

/**
  * Defines the time outs for unreachable tasks.
  */
case class UnreachableStrategy(
  unreachableInactiveAfter: FiniteDuration = UnreachableStrategy.DefaultTimeUntilInactive,
  unreachableExpungeAfter: FiniteDuration = UnreachableStrategy.DefaultTimeUntilExpunge)

object UnreachableStrategy {
  val DefaultTimeUntilInactive = 3.minutes
  val DefaultTimeUntilExpunge = 6.minutes

  implicit val unreachableStrategyValidator = validator[UnreachableStrategy] { strategy =>
    strategy.unreachableInactiveAfter should be >= 1.second
    strategy.unreachableInactiveAfter should be < strategy.unreachableExpungeAfter
  }
}
