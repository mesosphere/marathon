package mesosphere.marathon
package state

import com.wix.accord._
import com.wix.accord.dsl._

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration._

sealed trait UnreachableStrategy {
  def toProto: Protos.UnreachableStrategy
}

case object UnreachableDisabled extends UnreachableStrategy {
  val toProto: Protos.UnreachableStrategy =
    Protos.UnreachableStrategy.getDefaultInstance
}

/**
  * Defines the time outs for unreachable tasks.
  */
case class UnreachableEnabled(
    inactiveAfter: FiniteDuration = UnreachableEnabled.DefaultInactiveAfter,
    expungeAfter: FiniteDuration = UnreachableEnabled.DefaultExpungeAfter) extends UnreachableStrategy {

  def toProto: Protos.UnreachableStrategy =
    Protos.UnreachableStrategy.newBuilder.
      setExpungeAfterSeconds(expungeAfter.toSeconds).
      setInactiveAfterSeconds(inactiveAfter.toSeconds).
      build
}
object UnreachableEnabled {
  val DefaultInactiveAfter: FiniteDuration = 5.minutes
  val DefaultExpungeAfter: FiniteDuration = 10.minutes
  val default = UnreachableEnabled()

  implicit val unreachableEnabledValidator = validator[UnreachableEnabled] { strategy =>
    strategy.inactiveAfter should be >= 1.second
    strategy.inactiveAfter should be < strategy.expungeAfter
  }
}

object UnreachableStrategy {

  def default(resident: Boolean = false): UnreachableStrategy = {
    if (resident) UnreachableDisabled else UnreachableEnabled.default
  }

  def fromProto(unreachableStrategyProto: Protos.UnreachableStrategy): UnreachableStrategy = {
    if (unreachableStrategyProto.hasInactiveAfterSeconds && unreachableStrategyProto.hasExpungeAfterSeconds)
      UnreachableEnabled(
        inactiveAfter = unreachableStrategyProto.getInactiveAfterSeconds.seconds,
        expungeAfter = unreachableStrategyProto.getExpungeAfterSeconds.seconds)
    else
      UnreachableDisabled
  }

  implicit val unreachableStrategyValidator = new Validator[UnreachableStrategy] {
    def apply(strategy: UnreachableStrategy): Result = strategy match {
      case UnreachableDisabled =>
        Success
      case unreachableEnabled: UnreachableEnabled =>
        validate(unreachableEnabled)
    }
  }
}
