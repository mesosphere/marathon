package mesosphere.marathon
package state

import com.wix.accord.dsl._

import scala.concurrent.duration._

/**
  * Defines the time outs for unreachable tasks.
  */
case class UnreachableStrategy(
  inactiveAfter: FiniteDuration = UnreachableStrategy.DefaultInactiveAfter,
  expungeAfter: FiniteDuration = UnreachableStrategy.DefaultExpungeAfter)

object UnreachableStrategy {
  val DefaultInactiveAfter: FiniteDuration = 15.minutes
  val DefaultExpungeAfter: FiniteDuration = 7.days

  implicit val unreachableStrategyValidator = validator[UnreachableStrategy] { strategy =>
    strategy.inactiveAfter should be >= 1.second
    strategy.inactiveAfter should be < strategy.expungeAfter
  }
}
