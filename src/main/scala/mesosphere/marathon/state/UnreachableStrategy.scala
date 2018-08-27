package mesosphere.marathon
package state

import com.wix.accord.Descriptions.{Generic, Path}
import com.wix.accord._
import scala.concurrent.duration._

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
  val DefaultInactiveAfter: FiniteDuration = 0.seconds
  val DefaultExpungeAfter: FiniteDuration = 0.seconds
  val default = UnreachableEnabled()

  implicit val unreachableEnabledValidator = new Validator[UnreachableEnabled] {
    override def apply(unreachableEnabled: UnreachableEnabled): Result = {
      if (unreachableEnabled.inactiveAfter < 0.seconds)
        Failure(Set(
          RuleViolation(unreachableEnabled.inactiveAfter, UnreachableStrategy.InactiveAfterGreaterThanZeroMessage(unreachableEnabled.inactiveAfter), Path(Generic("inactiveAfterSeconds")))))
      else if (unreachableEnabled.inactiveAfter > unreachableEnabled.expungeAfter)
        Failure(Set(
          RuleViolation(
            unreachableEnabled.inactiveAfter,
            UnreachableStrategy.inactiveAfterSmallerThanExpungeAfterMessage(unreachableEnabled.inactiveAfter, unreachableEnabled.expungeAfter),
            Path(Generic("inactiveAfterSeconds")))))
      else
        Success
    }
  }
}

object UnreachableStrategy {
  def InactiveAfterGreaterThanZeroMessage(inactiveAfter: Duration) = s"inactiveAfterSeconds (${inactiveAfter.toSeconds}) must be greater than or equal to 0"
  def inactiveAfterSmallerThanExpungeAfterMessage(inactiveAfter: Duration, expungeAfter: Duration) =
    s"inactiveAfterSeconds (${inactiveAfter.toSeconds}) must be less or equal to expungeAfterSeconds, which is ${expungeAfter.toSeconds}"

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
