package mesosphere.marathon
package state

import com.wix.accord.dsl._

import scala.concurrent.duration._
import mesosphere.marathon.Protos

/**
  * Defines the time outs for unreachable tasks.
  */
case class UnreachableStrategy(
    inactiveAfter: FiniteDuration = UnreachableStrategy.DefaultEphemeralInactiveAfter,
    expungeAfter: FiniteDuration = UnreachableStrategy.DefaultEphemeralExpungeAfter) {

  def toProto: Protos.UnreachableStrategy =
    Protos.UnreachableStrategy.newBuilder.
      setExpungeAfterSeconds(expungeAfter.toSeconds).
      setInactiveAfterSeconds(inactiveAfter.toSeconds).
      build
}

object UnreachableStrategy {
  val DefaultEphemeralInactiveAfter: FiniteDuration = 5.minutes
  val DefaultEphemeralExpungeAfter: FiniteDuration = 10.minutes
  val DefaultResidentInactiveAfter: FiniteDuration = 1.hour
  val DefaultResidentExpungeAfter: FiniteDuration = 7.days

  val defaultEphemeral = UnreachableStrategy(DefaultEphemeralInactiveAfter, DefaultEphemeralExpungeAfter)
  val defaultResident = UnreachableStrategy(DefaultResidentInactiveAfter, DefaultResidentExpungeAfter)

  implicit val unreachableStrategyValidator = validator[UnreachableStrategy] { strategy =>
    strategy.inactiveAfter should be >= 1.second
    strategy.inactiveAfter should be < strategy.expungeAfter
  }

  def default(resident: Boolean): UnreachableStrategy = {
    if (resident) defaultResident else defaultEphemeral
  }

  def fromProto(unreachableStrategyProto: Protos.UnreachableStrategy): UnreachableStrategy = {
    UnreachableStrategy(
      inactiveAfter = unreachableStrategyProto.getInactiveAfterSeconds.seconds,
      expungeAfter = unreachableStrategyProto.getExpungeAfterSeconds.seconds)
  }
}
