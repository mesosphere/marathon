package mesosphere.marathon
package state

import com.wix.accord.dsl._

import scala.concurrent.duration._
import mesosphere.marathon.Protos

/**
  * Defines the time outs for unreachable tasks.
  */
case class UnreachableStrategy(
    inactiveAfter: FiniteDuration = UnreachableStrategy.DefaultInactiveAfter,
    expungeAfter: FiniteDuration = UnreachableStrategy.DefaultExpungeAfter) {

  def toProto: Protos.UnreachableStrategy =
    Protos.UnreachableStrategy.newBuilder.
      setExpungeAfterSeconds(expungeAfter.toSeconds).
      setInactiveAfterSeconds(inactiveAfter.toSeconds).
      build
}

object UnreachableStrategy {
  val DefaultInactiveAfter: FiniteDuration = 15.minutes
  val DefaultExpungeAfter: FiniteDuration = 7.days
  val default = UnreachableStrategy()

  implicit val unreachableStrategyValidator = validator[UnreachableStrategy] { strategy =>
    strategy.inactiveAfter should be >= 1.second
    strategy.inactiveAfter should be < strategy.expungeAfter
  }

  def fromProto(unreachableStrategyProto: Protos.UnreachableStrategy): UnreachableStrategy = {
    UnreachableStrategy(
      inactiveAfter = unreachableStrategyProto.getInactiveAfterSeconds.seconds,
      expungeAfter = unreachableStrategyProto.getExpungeAfterSeconds.seconds)
  }
}
