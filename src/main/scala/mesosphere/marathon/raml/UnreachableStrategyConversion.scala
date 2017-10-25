package mesosphere.marathon
package raml

import scala.concurrent.duration._

/**
  * Conversion from [[mesosphere.marathon.state.UnreachableStrategy]] to [[mesosphere.marathon.raml.UnreachableStrategy]].
  */
trait UnreachableStrategyConversion {

  def fromRaml(model: UnreachableStrategy): state.UnreachableStrategy = model match {
    case strategy: UnreachableEnabled =>
      state.UnreachableEnabled(
        inactiveAfter = strategy.inactiveAfterSeconds.seconds,
        expungeAfter = strategy.expungeAfterSeconds.seconds)
    case _: UnreachableDisabled =>
      state.UnreachableDisabled
  }

  def asRaml(model: state.UnreachableStrategy): UnreachableStrategy = model match {
    case strategy: state.UnreachableEnabled =>
      UnreachableEnabled(
        inactiveAfterSeconds = strategy.inactiveAfter.toSeconds,
        expungeAfterSeconds = strategy.expungeAfter.toSeconds)
    case state.UnreachableDisabled =>
      UnreachableDisabled.DefaultValue
  }

  implicit val unreachableProtoRamlWrites = Writes[Protos.UnreachableStrategy, UnreachableStrategy]{ proto =>
    if (proto.hasExpungeAfterSeconds && proto.hasInactiveAfterSeconds) {
      UnreachableEnabled(
        inactiveAfterSeconds = proto.getInactiveAfterSeconds,
        expungeAfterSeconds = proto.getExpungeAfterSeconds
      )
    } else {
      UnreachableDisabled.DefaultValue
    }
  }
}

object UnreachableStrategyConversion extends UnreachableStrategyConversion
